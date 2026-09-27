package org.aloeil.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.AndroidKeystoreReadingCipher
import org.aloeil.app.data.CorrectionOperationRow
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.DraftCodec
import org.aloeil.app.data.DraftRow
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingAad
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingPayload
import org.aloeil.app.data.ReadingPayloadCodec
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingRow
import org.aloeil.app.data.ReadingVersionRow
import org.aloeil.app.data.SealedPayload
import org.aloeil.app.data.Sitting
import org.aloeil.app.data.SittingPayloadCodec
import org.aloeil.app.data.SittingRow
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic old-key rows prove migration preserves data and fails closed on interruption. */
@RunWith(AndroidJUnit4::class)
class EncryptionMigrationDeviceTest {
    private fun legacySeal(alias: String, clear: ByteArray): SealedPayload {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        val key = generator.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return SealedPayload(cipher.iv, cipher.doFinal(clear))
    }

    private fun legacySealAgain(alias: String, clear: ByteArray): SealedPayload {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return SealedPayload(cipher.iv, cipher.doFinal(clear))
    }

    @Test
    fun oldRowsUpgradeWithoutLosingReadingHistoryOrDraftAndRejectSwaps() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val suffix = UUID.randomUUID().toString()
        val oldAlias = "aloeil-test-old-$suffix"
        val newAlias = "aloeil-test-new-$suffix"
        val newCipher = AndroidKeystoreReadingCipher(newAlias, oldAlias)
        try {
            val sitting = legacySeal(oldAlias, SittingPayloadCodec.encode(Sitting("sit", 1000, null)))
            db.readings().insertSitting(SittingRow("sit", sitting.nonce, sitting.ciphertext))
            val oldVersion = ReadingPayload("sit", 1100, Eye.LEFT, "12.0", timeZoneId = "UTC")
            val previous = legacySealAgain(oldAlias, ReadingPayloadCodec.encode(oldVersion))
            db.readings().insertVersion(ReadingVersionRow("first", 1, previous.nonce, previous.ciphertext))
            db.readings().insertCorrectionOperation(
                CorrectionOperationRow("first:correct:2", "first", 2),
            )
            val latest = oldVersion.copy(value = "12.3")
            val current = legacySealAgain(oldAlias, ReadingPayloadCodec.encode(latest))
            db.readings().insertReading(ReadingRow("first", current.nonce, current.ciphertext, 2, 1))
            val draft = DraftCheckpoint("sit", "first", "EYE", Eye.LEFT, "", "heading")
            val oldDraft = legacySealAgain(oldAlias, DraftCodec.encode(draft))
            db.readings().saveDraft(DraftRow(nonce = oldDraft.nonce, ciphertext = oldDraft.ciphertext))

            val repo = ReadingRepository(db.readings(), newCipher)
            repo.verifyReadable()
            check(repo.all().single().value == "12.3")
            check(repo.recoverDraft()?.first == draft)
            check(repo.exportArchive("synthetic-migration-only".toCharArray()).isNotEmpty())
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!store.containsAlias(oldAlias))
            check(store.containsAlias(newAlias))

            val original = db.readings().reading("first")!!
            val version = db.readings().version("first", 1)!!
            check(runCatching {
                newCipher.open(
                    SealedPayload(version.nonce, version.ciphertext),
                    ReadingAad.reading("first", 1, 0),
                )
            }.isFailure)
            db.readings().updateReading(original.copy(revision = 3))
            check(runCatching { repo.all() }.isFailure)
            db.readings().updateReading(original)
            check(repo.all().single().revision == 2L)
        } finally {
            db.close()
            newCipher.deleteKeyForRecovery()
        }
    }

    @Test
    fun damagedDraftDoesNotBlockSavedRowMigrationOrLaterOpens() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val suffix = UUID.randomUUID().toString()
        val oldAlias = "aloeil-test-old-$suffix"
        val newCipher = AndroidKeystoreReadingCipher("aloeil-test-new-$suffix", oldAlias)
        try {
            val sitting = legacySeal(oldAlias, SittingPayloadCodec.encode(Sitting("sit", 1000, null)))
            db.readings().insertSitting(SittingRow("sit", sitting.nonce, sitting.ciphertext))
            val fact = ReadingPayload("sit", 1100, Eye.LEFT, "12.3", timeZoneId = "UTC")
            val reading = legacySealAgain(oldAlias, ReadingPayloadCodec.encode(fact))
            db.readings().insertReading(ReadingRow("first", reading.nonce, reading.ciphertext, 1, 0))
            val draft = newCipher.seal(
                DraftCodec.encode(DraftCheckpoint("sit", "first", "EYE", Eye.LEFT, "", "heading")),
                ReadingAad.draft(),
            )
            val damaged = draft.ciphertext.clone().apply {
                this[0] = (this[0].toInt() xor 1).toByte()
            }
            db.readings().saveDraft(DraftRow(nonce = draft.nonce, ciphertext = damaged))

            val repo = ReadingRepository(db.readings(), newCipher)
            check(repo.verifyReadable())
            check(repo.all().single().value == "12.3")
            check(db.readings().draft() == null)
            check(repo.recoverDraft() == null)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!store.containsAlias(oldAlias))
            // Reopening after migration must not retry the discarded checkpoint.
            val reopened = ReadingRepository(db.readings(), newCipher)
            check(!reopened.verifyReadable())
            check(reopened.recoverDraft() == null)
            check(reopened.all().single().value == "12.3")
        } finally {
            db.close()
            newCipher.deleteKeyForRecovery()
        }
    }

    @Test
    fun corruptLegacyDraftAloneIsDiscardedBeforeLegacyKeyRetirement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val suffix = UUID.randomUUID().toString()
        val oldAlias = "aloeil-test-old-$suffix"
        val cipher = AndroidKeystoreReadingCipher("aloeil-test-new-$suffix", oldAlias)
        try {
            val draft = DraftCheckpoint("sit", "reading", "EYE", Eye.LEFT, "", "heading")
            val legacy = legacySeal(oldAlias, DraftCodec.encode(draft))
            val damaged = legacy.ciphertext.clone().apply {
                this[0] = (this[0].toInt() xor 1).toByte()
            }
            db.readings().saveDraft(DraftRow(nonce = legacy.nonce, ciphertext = damaged))
            val repo = ReadingRepository(db.readings(), cipher)
            check(repo.verifyReadable())
            check(db.readings().draft() == null)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!store.containsAlias(oldAlias))
            check(!ReadingRepository(db.readings(), cipher).verifyReadable())
        } finally {
            db.close()
            cipher.deleteKeyForRecovery()
        }
    }

    @Test
    fun onlyCorruptDraftIsDiscardedAndStaysGoneAfterReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val cipher = AndroidKeystoreReadingCipher("aloeil-test-only-draft-" + UUID.randomUUID())
        try {
            val draft = cipher.seal(
                DraftCodec.encode(DraftCheckpoint("sit", "first", "EYE", Eye.LEFT, "", "heading")),
                ReadingAad.draft(),
            )
            val damaged = draft.ciphertext.clone().apply {
                this[0] = (this[0].toInt() xor 1).toByte()
            }
            db.readings().saveDraft(DraftRow(nonce = draft.nonce, ciphertext = damaged))
            val repo = ReadingRepository(db.readings(), cipher)
            check(repo.verifyReadable())
            check(db.readings().draft() == null)
            check(repo.recoverDraft() == null)
            check(!ReadingRepository(db.readings(), cipher).verifyReadable())
        } finally {
            db.close()
            cipher.deleteKeyForRecovery()
        }
    }

    @Test
    fun failedUpgradeKeepsOldKeyAndRowsForRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val suffix = UUID.randomUUID().toString()
        val oldAlias = "aloeil-test-old-$suffix"
        val newCipher = AndroidKeystoreReadingCipher("aloeil-test-new-$suffix", oldAlias)
        try {
            val sitting = legacySeal(oldAlias, SittingPayloadCodec.encode(Sitting("sit", 1000, null)))
            db.readings().insertSitting(SittingRow("sit", sitting.nonce, sitting.ciphertext))
            val payload = ReadingPayload("sit", 1100, Eye.LEFT, "12.3", timeZoneId = "UTC")
            val current = legacySealAgain(oldAlias, ReadingPayloadCodec.encode(payload))
            db.readings().insertReading(ReadingRow("first", current.nonce, current.ciphertext, 1, 0))
            val broken = ReadingVersionRow("first", 1, ByteArray(12), ByteArray(24))
            db.readings().insertVersion(broken)

            val repo = ReadingRepository(db.readings(), newCipher)
            check(runCatching { repo.verifyReadable() }.isFailure)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(store.containsAlias(oldAlias))
            val after = db.readings().reading("first")!!
            check(after.nonce.contentEquals(current.nonce))
            check(after.ciphertext.contentEquals(current.ciphertext))

            val version = legacySealAgain(oldAlias, ReadingPayloadCodec.encode(payload))
            db.readings().updateVersion(broken.copy(nonce = version.nonce, ciphertext = version.ciphertext))
            repo.verifyReadable()
            check(!store.apply { load(null) }.containsAlias(oldAlias))
            check(repo.all().single().value == "12.3")
        } finally {
            db.close()
            newCipher.deleteKeyForRecovery()
        }
    }
}
