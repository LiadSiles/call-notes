// JNI bridge to whisper.cpp for com.callnotes.inference.whisper.WhisperNative.
//
// Text crosses the boundary as raw UTF-8 bytes, never via NewStringUTF: Whisper's BPE
// tokens can split a multi-byte character (every Hebrew letter is 2 bytes), and JNI
// aborts the process on invalid modified UTF-8. Kotlin joins the bytes and decodes.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "CallNotesWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Holder {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
    // Valid only during transcribe(): whisper_full calls back on the calling thread.
    JNIEnv *env = nullptr;
    jobject listener = nullptr;
    jmethodID onProgress = nullptr;
};

void log_cb(ggml_log_level level, const char *text, void *) {
    __android_log_write(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG, TAG, text);
}

bool abort_cb(void *data) {
    return static_cast<Holder *>(data)->abort.load();
}

void progress_cb(whisper_context *, whisper_state *, int progress, void *data) {
    auto *h = static_cast<Holder *>(data);
    if (h->env && h->listener && h->onProgress) {
        h->env->CallVoidMethod(h->listener, h->onProgress, (jint) progress);
        if (h->env->ExceptionCheck()) h->env->ExceptionClear();
    }
}

jbyteArray toBytes(JNIEnv *env, const std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

template <typename T, typename A>
A fill(JNIEnv *env, const std::vector<T> &v, A (JNIEnv::*make)(jsize),
       void (JNIEnv::*set)(A, jsize, jsize, const T *)) {
    A a = (env->*make)((jsize) v.size());
    (env->*set)(a, 0, (jsize) v.size(), v.data());
    return a;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_callnotes_inference_whisper_WhisperNative_init(JNIEnv *env, jobject, jstring jpath, jboolean dtw, jint aheads) {
    whisper_log_set(log_cb, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    // DTW word timing (used to split speakers by word) is incompatible with flash attention.
    cp.flash_attn = !dtw;
    cp.dtw_token_timestamps = dtw;
    cp.dtw_aheads_preset = (whisper_alignment_heads_preset) aheads;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    auto *h = new Holder();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_callnotes_inference_whisper_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Holder *>(handle);
    if (!h) return;
    whisper_free(h->ctx);
    delete h;
}

JNIEXPORT void JNICALL
Java_com_callnotes_inference_whisper_WhisperNative_abort(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Holder *>(handle);
    if (h) h->abort.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_callnotes_inference_whisper_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

/**
 * Returns null on failure or abort, else Object[]:
 *  0 byte[]  segment text bytes (concatenated)   1 int[] segment byte offsets (n+1)
 *  2 long[]  segment t0 (10 ms units)            3 long[] segment t1
 *  4 byte[]  token text bytes (concatenated)     5 int[] token byte offsets (n+1)
 *  6 long[]  token t0                            7 long[] token t1
 *  8 long[]  token t_dtw (-1 if none)            9 int[] token segment index
 */
JNIEXPORT jobjectArray JNICALL
Java_com_callnotes_inference_whisper_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray jsamples, jstring jlang,
        jint threads, jint beamSize, jstring jprompt, jboolean keepQuiet, jobject listener) {
    auto *h = reinterpret_cast<Holder *>(handle);
    if (!h || !h->ctx) return nullptr;
    h->abort.store(false);

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);
    // Words to expect (names, terms): shown to the decoder before every 30 s window.
    std::string prompt;
    if (jprompt) {
        const char *pr = env->GetStringUTFChars(jprompt, nullptr);
        prompt = pr;
        env->ReleaseStringUTFChars(jprompt, pr);
    }

    whisper_full_params p = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = language.c_str();
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;          // a bad window can't poison the next one
    p.suppress_nst = true;
    p.token_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    if (beamSize > 1) p.beam_search.beam_size = beamSize;
    if (keepQuiet) {
        // Second look at audio that has speech energy but got no words: never judge the
        // window "no speech" (that drops all of it), and no random-temperature retries.
        p.no_speech_thold = 1.0f;
        p.temperature_inc = 0.0f;
    }
    if (!prompt.empty()) {
        p.initial_prompt = prompt.c_str();
        p.carry_initial_prompt = true; // every window, not just the first
    }
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = h;
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = h;

    h->env = env;
    h->listener = listener;
    h->onProgress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        h->onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    }

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    int rc = whisper_full(h->ctx, p, samples, n);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    h->env = nullptr;
    h->listener = nullptr;

    if (rc != 0 || h->abort.load()) {
        LOGI("whisper_full returned %d (abort=%d)", rc, (int) h->abort.load());
        return nullptr;
    }

    const whisper_token eot = whisper_token_eot(h->ctx);
    std::string segBytes, tokBytes;
    std::vector<jint> segOff{0}, tokOff{0}, tokSeg;
    std::vector<jlong> segT0, segT1, tokT0, tokT1, tokDtw;

    const int nSeg = whisper_full_n_segments(h->ctx);
    for (int s = 0; s < nSeg; ++s) {
        segBytes += whisper_full_get_segment_text(h->ctx, s);
        segOff.push_back((jint) segBytes.size());
        segT0.push_back(whisper_full_get_segment_t0(h->ctx, s));
        segT1.push_back(whisper_full_get_segment_t1(h->ctx, s));
        const int nTok = whisper_full_n_tokens(h->ctx, s);
        for (int t = 0; t < nTok; ++t) {
            whisper_token_data d = whisper_full_get_token_data(h->ctx, s, t);
            if (d.id >= eot) continue; // special and timestamp tokens
            tokBytes += whisper_full_get_token_text(h->ctx, s, t);
            tokOff.push_back((jint) tokBytes.size());
            tokT0.push_back(d.t0);
            tokT1.push_back(d.t1);
            tokDtw.push_back(d.t_dtw);
            tokSeg.push_back(s);
        }
    }

    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(10, objCls, nullptr);
    env->SetObjectArrayElement(out, 0, toBytes(env, segBytes));
    env->SetObjectArrayElement(out, 1, fill(env, segOff, &JNIEnv::NewIntArray, &JNIEnv::SetIntArrayRegion));
    env->SetObjectArrayElement(out, 2, fill(env, segT0, &JNIEnv::NewLongArray, &JNIEnv::SetLongArrayRegion));
    env->SetObjectArrayElement(out, 3, fill(env, segT1, &JNIEnv::NewLongArray, &JNIEnv::SetLongArrayRegion));
    env->SetObjectArrayElement(out, 4, toBytes(env, tokBytes));
    env->SetObjectArrayElement(out, 5, fill(env, tokOff, &JNIEnv::NewIntArray, &JNIEnv::SetIntArrayRegion));
    env->SetObjectArrayElement(out, 6, fill(env, tokT0, &JNIEnv::NewLongArray, &JNIEnv::SetLongArrayRegion));
    env->SetObjectArrayElement(out, 7, fill(env, tokT1, &JNIEnv::NewLongArray, &JNIEnv::SetLongArrayRegion));
    env->SetObjectArrayElement(out, 8, fill(env, tokDtw, &JNIEnv::NewLongArray, &JNIEnv::SetLongArrayRegion));
    env->SetObjectArrayElement(out, 9, fill(env, tokSeg, &JNIEnv::NewIntArray, &JNIEnv::SetIntArrayRegion));
    return out;
}

} // extern "C"
