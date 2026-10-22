package com.callnotes.security

import android.content.Context
import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An encrypted recording: "CNE1", a 16-byte IV, then the original file encrypted with
 * AES-256-CTR under [KeyVault]'s data key. CTR lets the player read any position directly
 * ([Source]); the name is the original name plus ".enc".
 */
object SealedFile {

    private val MAGIC = "CNE1".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 20
    const val SUFFIX = ".enc"

    fun isSealed(path: String) = path.endsWith(SUFFIX)

    /** Encrypts [plain] next to itself; returns the new file (the original is left to the caller). */
    fun seal(context: Context, plain: File): File {
        val out = File(plain.path + SUFFIX)
        val part = File(out.path + ".part")
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = cipher(context, Cipher.ENCRYPT_MODE, iv)
        plain.inputStream().buffered(1 shl 16).use { input ->
            part.outputStream().buffered(1 shl 16).use { output ->
                output.write(MAGIC); output.write(iv)
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    output.write(cipher.update(buf, 0, n))
                }
                output.write(cipher.doFinal())
            }
        }
        check(part.length() == plain.length() + HEADER) { "sealed size mismatch" }
        check(part.renameTo(out)) { "rename failed" }
        return out
    }

    /** Decrypts [sealed] to [out]. */
    fun unseal(context: Context, sealed: File, out: File): File {
        val part = File(out.path + ".part")
        RandomAccessFile(sealed, "r").use { f ->
            val iv = header(f)
            val cipher = cipher(context, Cipher.DECRYPT_MODE, iv)
            part.outputStream().buffered(1 shl 16).use { output ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = f.read(buf); if (n < 0) break
                    output.write(cipher.update(buf, 0, n))
                }
                output.write(cipher.doFinal())
            }
        }
        check(part.renameTo(out)) { "rename failed" }
        return out
    }

    /** The original name ("call_1.m4a" for "call_1.m4a.enc"). */
    fun plainName(sealed: File) = sealed.name.removeSuffix(SUFFIX)

    private fun header(f: RandomAccessFile): ByteArray {
        val magic = ByteArray(4).also { f.readFully(it) }
        check(magic.contentEquals(MAGIC)) { "not a sealed file" }
        return ByteArray(16).also { f.readFully(it) }
    }

    private fun cipher(context: Context, mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, SecretKeySpec(KeyVault.dataKey(context), "AES"), IvParameterSpec(iv))
        }

    /** Counter block for byte [offset]: IV + offset/16 (big-endian, 128-bit add). */
    internal fun counterAt(iv: ByteArray, offset: Long): ByteArray {
        val out = iv.copyOf()
        var carry = offset / 16
        var i = 15
        while (carry != 0L && i >= 0) {
            val sum = (out[i].toLong() and 0xFF) + (carry and 0xFF)
            out[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            i--
        }
        return out
    }

    /** Random-access decrypting source for MediaPlayer / MediaExtractor. */
    class Source(context: Context, file: File) : MediaDataSource() {
        private val raf = RandomAccessFile(file, "r")
        private val iv = header(raf)
        private val key = SecretKeySpec(KeyVault.dataKey(context), "AES")
        private val size = file.length() - HEADER
        private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        override fun getSize(): Long = size

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= this.size) return -1
            val n = minOf(size.toLong(), this.size - position).toInt()
            val skip = (position % 16).toInt()
            val enc = ByteArray(skip + n)
            raf.seek(HEADER + position - skip)
            raf.readFully(enc)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(counterAt(iv, position)))
            val plain = cipher.doFinal(enc)
            System.arraycopy(plain, skip, buffer, offset, n)
            return n
        }

        override fun close() = raf.close()
    }
}
