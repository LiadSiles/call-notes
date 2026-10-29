// Lets the app hand pages of a memory-mapped file back to the kernel (MADV_PAGEOUT). Gemma's
// model file is mapped by LiteRT-LM; pages it touched once stay counted in the app's memory
// (PSS) while the phone has RAM to spare, and HyperOS stops apps past 6 GB of PSS. Paged-out
// pages are read back from storage if they are needed again: nothing is lost, only time.
#include <jni.h>
#include <sys/mman.h>
#include <cerrno>
#include <cstdio>
#include <cstring>

#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21  // Linux 5.4
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_com_callnotes_processing_NativeMemory_pageOut(JNIEnv* env, jobject /* this */, jstring jname) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    if (name == nullptr) return -ENOMEM;
    const size_t nameLen = strlen(name);
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) {
        const int err = errno;
        env->ReleaseStringUTFChars(jname, name);
        return -err;
    }
    char line[4096];
    jlong advised = 0;
    int lastError = 0;
    while (fgets(line, sizeof line, maps) != nullptr) {
        unsigned long start = 0, end = 0;
        if (sscanf(line, "%lx-%lx", &start, &end) != 2 || end <= start) continue;
        char* path = strchr(line, '/');
        if (path == nullptr) continue;
        const size_t len = strcspn(path, "\n");
        path[len] = '\0';
        // The file itself: its name ends the path, right after a '/'.
        if (len <= nameLen || path[len - nameLen - 1] != '/' || strcmp(path + len - nameLen, name) != 0) continue;
        if (madvise(reinterpret_cast<void*>(start), end - start, MADV_PAGEOUT) == 0) advised += static_cast<jlong>(end - start);
        else lastError = errno;
    }
    fclose(maps);
    env->ReleaseStringUTFChars(jname, name);
    return advised > 0 ? advised : -lastError;
}
