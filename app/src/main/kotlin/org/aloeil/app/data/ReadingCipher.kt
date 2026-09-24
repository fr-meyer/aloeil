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
    fun seal(plaintext: ByteArray, aad: ByteArray): SealedPayload
    fun open(payload: SealedPayload, aad: ByteArray): ByteArray
}

class MissingReadingKeyException : IllegalStateException("Local reading key is missing")

/** The AES key stays in Android Keystore; the database only receives ciphertext. */
class AndroidKeystoreReadingCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val legacyAlias: String? = if (alias == DEFAULT_ALIAS) LEGACY_ALIAS else null,
) : ReadingCipher {
    private fun existingKey(name: String): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(name, null) as? SecretKey

    private fun createKey(): SecretKey {
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

    override fun seal(plaintext: ByteArray, aad: ByteArray): SealedPayload = synchronized(keyLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, existingKey(alias) ?: createKey())
        cipher.updateAAD(aad)
        SealedPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun open(payload: SealedPayload, aad: ByteArray): ByteArray = synchronized(keyLock) {
        require(payload.nonce.size == 12) { "Invalid encrypted reading nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingKey(alias) ?: throw MissingReadingKeyException(),
            GCMParameterSpec(128, payload.nonce),
        )
        cipher.updateAAD(aad)
        cipher.doFinal(payload.ciphertext)
    }

    /** Used only inside the atomic database migration; never by normal reads. */
    fun openLegacy(payload: SealedPayload): ByteArray = synchronized(keyLock) {
        require(payload.nonce.size == 12) { "Invalid encrypted reading nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            legacyAlias?.let(::existingKey) ?: throw MissingReadingKeyException(),
            GCMParameterSpec(128, payload.nonce),
        )
        cipher.doFinal(payload.ciphertext)
    }

    /** Remove the old key only after the database migration transaction commits. */
    fun deleteLegacyKeyAfterMigration() = synchronized(keyLock) {
        val old = legacyAlias ?: return@synchronized
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(old)) store.deleteEntry(old)
    }

    /** Called only after the user confirms deletion of the unreadable local database. */
    fun deleteKeyForRecovery() = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (listOfNotNull(alias, legacyAlias)).forEach { name ->
            if (store.containsAlias(name)) store.deleteEntry(name)
        }
    }

    companion object {
        private const val DEFAULT_ALIAS = "aloeil-reading-v2"
        private const val LEGACY_ALIAS = "aloeil-reading-v1"
        private val keyLock = Any()
    }
}
