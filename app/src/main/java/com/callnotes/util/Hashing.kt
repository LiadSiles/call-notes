package com.callnotes.util

import java.io.File
import java.security.MessageDigest

/** File checksums: model verification and the before/after fingerprint of recordings in the log. */
object Hashing {
    /** SHA-256 of a file as lowercase hex (streamed; works for multi-GB model files). */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
