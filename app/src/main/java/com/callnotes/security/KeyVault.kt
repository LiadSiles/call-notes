package com.callnotes.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The app's data key (256 bit), used for the database and the recordings when extra
 * encryption is on. It is stored only wrapped (AES-GCM) by a key that lives in the
 * phone's Android Keystore (hardware-backed where available) and never leaves it, so the
 * files alone — a copied data folder, a forensic image — can't be read.
 *
 * If the Keystore key is lost (very rare: some phones wipe it on a factory reset of the
 * lock screen), the data key can't be unwrapped and the encrypted data is unreadable.
 */
object KeyVault {

    private const val ALIAS = "callnotes_master"
    private const val KEY_FILE = "keys/data.key"
    @Volatile private var cached: ByteArray? = null

    class KeyLost(cause: Throwable) : Exception("data key can't be unwrapped", cause)

    fun hasKey(context: Context): Boolean = File(context.filesDir, KEY_FILE).isFile

    /** The data key, created on first use. Throws [KeyLost] if it exists but can't be unwrapped. */
    @Synchronized
    fun dataKey(context: Context): ByteArray {
        cached?.let { return it }
        val file = File(context.filesDir, KEY_FILE)
        val key = if (file.isFile) {
            try {
                unwrap(file.readBytes())
            } catch (e: Exception) {
                throw KeyLost(e)
            }
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { k ->
                file.parentFile!!.mkdirs()
                val tmp = File(file.path + ".part")
                tmp.writeBytes(wrap(k))
                check(tmp.renameTo(file)) { "can't store the data key" }
            }
        }
        cached = key
        return key
    }

    /** Forgets the data key and the Keystore key (after the key was found to be lost). */
    @Synchronized
    fun reset(context: Context) {
        cached = null
        File(context.filesDir, KEY_FILE).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    /** SQLCipher raw-key form: x'<64 hex>' (skips the slow passphrase derivation). */
    fun sqlKey(context: Context): ByteArray =
        ("x'" + dataKey(context).joinToString("") { "%02x".format(it) } + "'").toByteArray(Charsets.US_ASCII)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        return c.iv + c.doFinal(key) // 12-byte IV, then ciphertext + tag
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}
