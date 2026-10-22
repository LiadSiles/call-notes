// On-device quantization of a whisper.cpp ggml model (f16 → q8_0 / q5_0), so a faster,
// smaller copy of the downloaded model can be made without any server. Same file format
// and tensor rules as whisper.cpp's examples/quantize, but streamed row by row (low memory),
// with progress and cancel.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "ggml.h"

#define TAG "CallNotesQuant"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_abort{false};

struct File {
    FILE *f = nullptr;
    explicit File(const char *path, const char *mode) { f = fopen(path, mode); }
    ~File() { if (f) fclose(f); }
    bool read(void *p, size_t n) { return fread(p, 1, n, f) == n; }
    bool write(const void *p, size_t n) { return fwrite(p, 1, n, f) == n; }
};

template <typename T> bool copy(File &in, File &out, T &v) { return in.read(&v, sizeof v) && out.write(&v, sizeof v); }

bool copy_bytes(File &in, File &out, size_t n, std::vector<uint8_t> &buf) {
    buf.resize(n);
    return in.read(buf.data(), n) && out.write(buf.data(), n);
}

bool skip_quant(const std::string &name) {
    return name == "encoder.conv1.bias" || name == "encoder.conv2.bias" ||
           name == "encoder.positional_embedding" || name == "decoder.positional_embedding";
}

// Returns "" on success, else an error message.
std::string quantize(const char *in_path, const char *out_path, ggml_ftype ftype, ggml_type qtype,
                     JNIEnv *env, jobject listener, jmethodID on_progress) {
    File in(in_path, "rb");
    if (!in.f) return "cannot open input";
    File out(out_path, "wb");
    if (!out.f) return "cannot open output";
    fseek(in.f, 0, SEEK_END);
    const long total = ftell(in.f);
    fseek(in.f, 0, SEEK_SET);

    uint32_t magic;
    if (!copy(in, out, magic) || magic != GGML_FILE_MAGIC) return "bad magic";

    int32_t hp[11]; // n_vocab .. n_mels, ftype
    if (!in.read(hp, sizeof hp)) return "truncated header";
    if (hp[10] % GGML_QNT_VERSION_FACTOR != GGML_FTYPE_MOSTLY_F16 && hp[10] != GGML_FTYPE_ALL_F32)
        return "input is already quantized";
    hp[10] = GGML_QNT_VERSION * GGML_QNT_VERSION_FACTOR + ftype;
    if (!out.write(hp, sizeof hp)) return "write failed";

    std::vector<uint8_t> buf;
    // Mel filters.
    int32_t n_mel, n_fft;
    if (!copy(in, out, n_mel) || !copy(in, out, n_fft)) return "truncated filters";
    if (!copy_bytes(in, out, (size_t) n_mel * n_fft * sizeof(float), buf)) return "truncated filters";
    // Vocabulary.
    int32_t n_vocab;
    if (!copy(in, out, n_vocab)) return "truncated vocab";
    for (int i = 0; i < n_vocab; i++) {
        uint32_t len;
        if (!copy(in, out, len) || !copy_bytes(in, out, len, buf)) return "truncated vocab";
    }

    std::vector<ggml_fp16_t> rows16;
    std::vector<float> rows32;
    std::vector<uint8_t> q;
    int last_pct = -1;

    while (true) {
        int32_t n_dims, length, ttype;
        if (!in.read(&n_dims, sizeof n_dims)) break; // end of file
        if (!in.read(&length, sizeof length) || !in.read(&ttype, sizeof ttype)) return "truncated tensor header";
        if (n_dims < 1 || n_dims > 4 || length <= 0 || length > 512) return "bad tensor header";
        int32_t ne[4] = {1, 1, 1, 1};
        int64_t nelements = 1;
        for (int i = 0; i < n_dims; i++) {
            if (!in.read(&ne[i], sizeof ne[i])) return "truncated tensor header";
            nelements *= ne[i];
        }
        std::string name(length, '\0');
        if (!in.read(&name[0], length)) return "truncated tensor name";

        const bool quant = n_dims == 2 && !skip_quant(name) &&
                           (ttype == GGML_TYPE_F16 || ttype == GGML_TYPE_F32) &&
                           ne[0] % ggml_blck_size(qtype) == 0;
        const int32_t out_type = quant ? (int32_t) qtype : ttype;

        if (!out.write(&n_dims, sizeof n_dims) || !out.write(&length, sizeof length) ||
            !out.write(&out_type, sizeof out_type)) return "write failed";
        for (int i = 0; i < n_dims; i++) if (!out.write(&ne[i], sizeof ne[i])) return "write failed";
        if (!out.write(name.data(), length)) return "write failed";

        if (!quant) {
            const size_t bytes = (size_t) nelements * ggml_type_size((ggml_type) ttype) / ggml_blck_size((ggml_type) ttype);
            if (!copy_bytes(in, out, bytes, buf)) return "truncated tensor data";
        } else {
            const int64_t n_per_row = ne[0];
            const int64_t nrows = nelements / n_per_row;
            const int64_t chunk = 256;
            for (int64_t r = 0; r < nrows; r += chunk) {
                if (g_abort.load()) return "aborted";
                const int64_t n = std::min(chunk, nrows - r);
                const size_t count = (size_t) (n * n_per_row);
                rows32.resize(count);
                if (ttype == GGML_TYPE_F16) {
                    rows16.resize(count);
                    if (!in.read(rows16.data(), count * sizeof(ggml_fp16_t))) return "truncated tensor data";
                    ggml_fp16_to_fp32_row(rows16.data(), rows32.data(), (int64_t) count);
                } else if (!in.read(rows32.data(), count * sizeof(float))) {
                    return "truncated tensor data";
                }
                q.resize(ggml_row_size(qtype, n_per_row) * n);
                const size_t size = ggml_quantize_chunk(qtype, rows32.data(), q.data(), 0, n, n_per_row, nullptr);
                if (!out.write(q.data(), size)) return "write failed";
            }
        }
        if (g_abort.load()) return "aborted";
        const int pct = (int) (100.0 * ftell(in.f) / (double) total);
        if (pct != last_pct && listener) {
            last_pct = pct;
            env->CallVoidMethod(listener, on_progress, (jint) pct);
            if (env->ExceptionCheck()) { env->ExceptionClear(); }
        }
    }
    if (fflush(out.f) != 0) return "write failed";
    return "";
}

} // namespace

extern "C" {

/** Returns null on success, else the reason. type: 8 = q8_0, 5 = q5_0. */
JNIEXPORT jstring JNICALL
Java_com_callnotes_inference_whisper_WhisperNative_quantize(
        JNIEnv *env, jobject, jstring jin, jstring jout, jint type, jobject listener) {
    ggml_ftype ftype;
    ggml_type qtype;
    switch (type) {
        case 8: ftype = GGML_FTYPE_MOSTLY_Q8_0; qtype = GGML_TYPE_Q8_0; break;
        case 5: ftype = GGML_FTYPE_MOSTLY_Q5_0; qtype = GGML_TYPE_Q5_0; break;
        default: return env->NewStringUTF("unsupported type");
    }
    g_abort.store(false);
    // Initialises ggml's fp16 tables (as whisper.cpp's quantize tool does).
    ggml_init_params ip = {0, nullptr, false};
    ggml_free(ggml_init(ip));

    jmethodID on_progress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        on_progress = env->GetMethodID(cls, "onProgress", "(I)V");
    }
    const char *in = env->GetStringUTFChars(jin, nullptr);
    const char *out = env->GetStringUTFChars(jout, nullptr);
    const std::string err = quantize(in, out, ftype, qtype, env, listener, on_progress);
    env->ReleaseStringUTFChars(jin, in);
    env->ReleaseStringUTFChars(jout, out);
    if (!err.empty()) LOGE("quantize: %s", err.c_str());
    return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

JNIEXPORT void JNICALL
Java_com_callnotes_inference_whisper_WhisperNative_quantizeAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

}
