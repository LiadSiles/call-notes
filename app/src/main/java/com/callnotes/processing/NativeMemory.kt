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

    /**
     * Moves this process's large anonymous buffers (at least [minBytes] each: the model's own
     * memory, not the Java heap) to the phone's compressed swap (MADV_PAGEOUT). Pages in use
     * come back when touched. Returns the bytes of mappings advised, or a negative errno.
     */
    fun releaseAnonymous(minBytes: Long): Long = if (loaded) pageOutAnonymous(minBytes) else -1

    private external fun pageOut(fileName: String): Long
    private external fun pageOutAnonymous(minBytes: Long): Long
}
