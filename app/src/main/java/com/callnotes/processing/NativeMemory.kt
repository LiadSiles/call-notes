package com.callnotes.processing

/** Native helper of [MemoryGuard] (app/src/main/cpp/memory_jni.cpp). */
object NativeMemory {

    private val loaded: Boolean = runCatching { System.loadLibrary("callnotes_memory") }.isSuccess

    /**
     * Hands this process's pages of every mapping of the file named [fileName] back to the
     * kernel (MADV_PAGEOUT). Returns the bytes of mappings released, 0 if the file isn't
     * mapped, or a negative errno.
     */
    fun release(fileName: String): Long = if (loaded) pageOut(fileName) else -1

    private external fun pageOut(fileName: String): Long
}
