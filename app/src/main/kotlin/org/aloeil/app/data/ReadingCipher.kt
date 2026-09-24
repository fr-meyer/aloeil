package org.aloeil.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SealedPayload(val nonce: ByteArray, val ciphertext: ByteArray)

interface ReadingCipher {
    fun seal(plaintext: ByteArray): SealedPayload
    fun open(payload: SealedPayload): ByteArray
}

/** The AES key stays in Android Keystore; the database only receives ciphertext. */
class AndroidKeystoreReadingCipher : ReadingCipher {
    private val alias = "aloeil-reading-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plaintext: ByteArray): SealedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return SealedPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun open(payload: SealedPayload): ByteArray {
        require(payload.nonce.size == 12) { "Invalid encrypted reading nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.nonce))
        return cipher.doFinal(payload.ciphertext)
    }
}
