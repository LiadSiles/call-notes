package com.callnotes.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SealedFileTest {

    @Test fun randomAccessDecryptMatchesStream() {
        val rnd = Random(7)
        val key = SecretKeySpec(ByteArray(32).also(rnd::nextBytes), "AES")
        // An IV near the 64-bit boundary checks carries across bytes.
        val iv = ByteArray(16) { if (it >= 8) 0xFF.toByte() else it.toByte() }
        val plain = ByteArray(10_000).also(rnd::nextBytes)
        val enc = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv)); doFinal(plain)
        }
        for ((pos, len) in listOf(0 to 10, 5 to 100, 16 to 16, 4097 to 3000, 9_990 to 10)) {
            val skip = pos % 16
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(SealedFile.counterAt(iv, pos.toLong())))
            val out = c.doFinal(enc, pos - skip, skip + len)
            assertArrayEquals("at $pos", plain.copyOfRange(pos, pos + len), out.copyOfRange(skip, skip + len))
        }
    }
}
