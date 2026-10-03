package org.aloeil.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.EOFException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.ArchiveCodec
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.DraftRow
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingAad
import org.aloeil.app.data.ReadingCipher
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingRow
import org.aloeil.app.data.SealedPayload
import org.aloeil.app.data.SittingRow
import org.aloeil.app.data.withDecryptedPayload
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Retain only synthetic cipher-return buffers to observe cleanup in real source paths. */
@RunWith(AndroidJUnit4::class)
class DecryptedBufferCleanupDeviceTest {
    private lateinit var database: ReadingDatabase
    private lateinit var cipher: TrackedCipher
    private lateinit var repository: ReadingRepository

    private class TrackedCipher : ReadingCipher {
        private val real = SyntheticCipher()
        private data class Opened(
            val clear: ByteArray,
            val nonce: ByteArray, val nonceBefore: ByteArray,
            val ciphertext: ByteArray, val ciphertextBefore: ByteArray,
            val aad: ByteArray, val aadBefore: ByteArray,
        )
        private val opened = ConcurrentLinkedQueue<Opened>()
        val nextSealFailure = AtomicReference<RuntimeException?>(null)

        override fun seal(plaintext: ByteArray, aad: ByteArray): SealedPayload {
            nextSealFailure.getAndSet(null)?.let { throw it }
            return real.seal(plaintext, aad)
        }

        override fun open(payload: SealedPayload, aad: ByteArray): ByteArray {
            val clear = real.open(payload, aad)
            check(clear.any { it != 0.toByte() }) { "Synthetic fixture must make zeroing observable" }
            opened.add(Opened(clear, payload.nonce, payload.nonce.clone(),
                payload.ciphertext, payload.ciphertext.clone(), aad, aad.clone()))
            return clear
        }

        fun assertWipedAndReset(minimumOpens: Int = 1) {
            val results = opened.toList()
            check(results.size >= minimumOpens) { "Expected a real synthetic decryption" }
            results.forEach {
                check(it.clear.all { value -> value == 0.toByte() }) { "Decryption result was not cleared" }
                check(it.nonce.contentEquals(it.nonceBefore)) { "Caller nonce changed" }
                check(it.ciphertext.contentEquals(it.ciphertextBefore)) { "Caller ciphertext changed" }
                check(it.aad.contentEquals(it.aadBefore)) { "Caller AAD changed" }
            }
            opened.clear()
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        cipher = TrackedCipher()
        repository = ReadingRepository(database.readings(), cipher, { "UTC" }) { 1_700_000_000_000L }
    }

    @After
    fun tearDown() {
        try {
            cipher.assertWipedAndReset(minimumOpens = 0)
        } finally {
            database.close()
        }
    }

    private suspend fun seedReading() {
        repository.startSitting("synthetic-cleanup-sitting")
        repository.record("synthetic-cleanup-reading", "synthetic-cleanup-sitting", Eye.LEFT, "12.3")
        cipher.assertWipedAndReset(minimumOpens = 2)
    }

    @Test
    fun repositoryRecoverySnapshotsCorrectionsUndoAndExportClearReturnedBuffers() = runBlocking {
        seedReading()
        repository.saveDraft(DraftCheckpoint("synthetic-cleanup-sitting", "synthetic-cleanup-reading",
            "REVIEW", Eye.LEFT, "12.3", "heading"))
        val recovered = checkNotNull(repository.recoverDraft())
        check(recovered.first.readingId == "synthetic-cleanup-reading" && recovered.second?.revision == 1L)
        cipher.assertWipedAndReset(minimumOpens = 3)
        check(repository.all().single().id == "synthetic-cleanup-reading")
        cipher.assertWipedAndReset()
        check(repository.allSittings().single().id == "synthetic-cleanup-sitting")
        cipher.assertWipedAndReset()
        check(repository.currentFactsSnapshot().first.single().revision == 1L)
        cipher.assertWipedAndReset(minimumOpens = 2)
        check(repository.correctEye("synthetic-cleanup-correction", "synthetic-cleanup-reading", 1, Eye.RIGHT)?.revision == 2L)
        cipher.assertWipedAndReset(minimumOpens = 2)
        check(repository.undoCorrection("synthetic-cleanup-undo", "synthetic-cleanup-reading", 2)?.revision == 3L)
        cipher.assertWipedAndReset(minimumOpens = 3)
        val secret = "synthetic-cleanup-password".toCharArray()
        var archive: ByteArray? = null
        try {
            val bytes = repository.exportArchive(secret)
            archive = bytes
            cipher.assertWipedAndReset(minimumOpens = 4)
            val restored = ArchiveCodec.decode(bytes, secret)
            check(restored.readings.single().revision == 3L && restored.versions.size == 2)
        } finally {
            archive?.fill(0)
            secret.fill('\u0000')
        }
    }

    @Test
    fun malformedReadingDecoderClearsItsBufferAndPreservesTheEncryptedRow() = runBlocking {
        listOf(byteArrayOf(0, 0, 0, 99), byteArrayOf(1)).forEachIndexed { index, malformed ->
            val id = "synthetic-malformed-reading-$index"
            val sealed = cipher.seal(malformed, ReadingAad.reading(id, 1, 0))
            database.readings().insertReading(ReadingRow(id, sealed.nonce, sealed.ciphertext, 1, 0))
            val failure = runCatching { repository.all() }.exceptionOrNull()
            check(if (index == 0) failure is IllegalArgumentException else failure is EOFException)
            cipher.assertWipedAndReset()
            check(database.readings().reading(id)?.ciphertext?.contentEquals(sealed.ciphertext) == true)
            database.readings().removeReading(id)
        }
    }

    @Test
    fun malformedSittingDecoderClearsItsBufferAndPreservesTheEncryptedRow() = runBlocking {
        val sealed = cipher.seal(byteArrayOf(0, 0, 0, 99), ReadingAad.sitting("synthetic-malformed-sitting"))
        database.readings().insertSitting(SittingRow("synthetic-malformed-sitting", sealed.nonce, sealed.ciphertext))
        check(runCatching { repository.allSittings() }.exceptionOrNull() is IllegalArgumentException)
        cipher.assertWipedAndReset()
        check(database.readings().sitting("synthetic-malformed-sitting")?.ciphertext?.contentEquals(sealed.ciphertext) == true)
    }

    @Test
    fun malformedDraftDecoderClearsItsBufferWithoutDeletingTheCheckpoint() = runBlocking {
        val sealed = cipher.seal(byteArrayOf(0, 0, 0, 99), ReadingAad.draft())
        database.readings().saveDraft(DraftRow(nonce = sealed.nonce, ciphertext = sealed.ciphertext))
        check(runCatching { repository.recoverDraft() }.exceptionOrNull() is IllegalArgumentException)
        cipher.assertWipedAndReset()
        check(database.readings().draft()?.ciphertext?.contentEquals(sealed.ciphertext) == true)
    }

    @Test
    fun malformedVersionUndoAndExportClearBuffersWithoutChangingTheCurrentRevision() = runBlocking {
        seedReading()
        check(repository.correctEye("synthetic-version-correction", "synthetic-cleanup-reading", 1, Eye.RIGHT)?.revision == 2L)
        cipher.assertWipedAndReset(minimumOpens = 2)
        val prior = checkNotNull(database.readings().version("synthetic-cleanup-reading", 1))
        val sealed = cipher.seal(byteArrayOf(0, 0, 0, 99), ReadingAad.version(prior.readingId, prior.revision))
        check(database.readings().updateVersion(prior.copy(nonce = sealed.nonce, ciphertext = sealed.ciphertext)) == 1)
        check(runCatching { repository.undoCorrection("synthetic-bad-version-undo", "synthetic-cleanup-reading", 2) }
            .exceptionOrNull() is IllegalArgumentException)
        cipher.assertWipedAndReset()
        val secret = "synthetic-cleanup-password".toCharArray()
        try {
            check(runCatching { repository.exportArchive(secret) }.exceptionOrNull() is IllegalArgumentException)
            cipher.assertWipedAndReset(minimumOpens = 3)
        } finally {
            secret.fill('\u0000')
        }
        check(database.readings().reading("synthetic-cleanup-reading")?.revision == 2L)
    }

    @Test
    fun acknowledgementResealsBeforeClearingAndPreservesReadableConfirmedMetadata() = runBlocking {
        seedReading()
        check(repository.acknowledgeReplica("synthetic-cleanup-reading", 1))
        cipher.assertWipedAndReset()
        val reading = repository.all().single()
        check(reading.revision == 1L && reading.replicaConfirmedRevision == 1L)
        check(database.readings().dueOutbox(Long.MAX_VALUE).isEmpty())
        cipher.assertWipedAndReset()
    }

    @Test
    fun acknowledgementResealFailureClearsTheBufferAndPreservesRowAndOutbox() = runBlocking {
        seedReading()
        val before = checkNotNull(database.readings().reading("synthetic-cleanup-reading"))
        val failure = IllegalStateException("Synthetic reseal failure")
        cipher.nextSealFailure.set(failure)
        check(runCatching { repository.acknowledgeReplica(before.id, before.revision) }.exceptionOrNull() === failure)
        cipher.assertWipedAndReset()
        val after = checkNotNull(database.readings().reading(before.id))
        check(after.revision == before.revision && after.replicaConfirmedRevision == before.replicaConfirmedRevision)
        check(after.nonce.contentEquals(before.nonce) && after.ciphertext.contentEquals(before.ciphertext))
        check(database.readings().dueOutbox(Long.MAX_VALUE).size == 1)
    }

    @Test
    fun correctionResealFailureKeepsOriginalRevisionAndClearsTheDecodedOldPayload() = runBlocking {
        seedReading()
        val before = checkNotNull(database.readings().reading("synthetic-cleanup-reading"))
        val failure = IllegalStateException("Synthetic correction reseal failure")
        cipher.nextSealFailure.set(failure)
        check(runCatching { repository.correctEye("synthetic-failed-correction", before.id, 1, Eye.RIGHT) }
            .exceptionOrNull() === failure)
        cipher.assertWipedAndReset()
        val after = checkNotNull(database.readings().reading(before.id))
        check(after.revision == 1L && after.ciphertext.contentEquals(before.ciphertext))
        check(database.readings().allVersions().isEmpty() && database.readings().allCorrectionOperations().isEmpty())
    }

    @Test
    fun duplicateAndConflictingRestoreClearAllComparisonBuffers() = runBlocking {
        seedReading()
        check(repository.correctEye("synthetic-restore-correction", "synthetic-cleanup-reading", 1, Eye.RIGHT)?.revision == 2L)
        cipher.assertWipedAndReset(minimumOpens = 2)
        val secret = "synthetic-cleanup-password".toCharArray()
        var archive: ByteArray? = null
        var conflicting: ByteArray? = null
        try {
            val bytes = repository.exportArchive(secret)
            archive = bytes
            cipher.assertWipedAndReset(minimumOpens = 3)
            val before = checkNotNull(database.readings().reading("synthetic-cleanup-reading"))
            check(repository.importArchive(bytes, secret) == 0)
            cipher.assertWipedAndReset(minimumOpens = 5)
            val bundle = ArchiveCodec.decode(bytes, secret)
            val changed = ArchiveCodec.encode(bundle.copy(readings = listOf(bundle.readings.single().copy(value = "99.9"))), secret)
            conflicting = changed
            check(runCatching { repository.importArchive(changed, secret) }.exceptionOrNull() is IllegalArgumentException)
            cipher.assertWipedAndReset(minimumOpens = 5)
            val after = checkNotNull(database.readings().reading(before.id))
            check(after.revision == before.revision && after.ciphertext.contentEquals(before.ciphertext))
            check(database.readings().allVersions().size == 1 && database.readings().allCorrectionOperations().size == 1)
        } finally {
            archive?.fill(0)
            conflicting?.fill(0)
            secret.fill('\u0000')
        }
    }

    @Test
    fun rejectedOpenAndFinishedSittingWritesStillClearDaoDecodedBuffers() = runBlocking {
        seedReading()
        check(runCatching { repository.startSitting("synthetic-second-open") }.exceptionOrNull() is IllegalArgumentException)
        cipher.assertWipedAndReset()
        check(repository.finishSitting("synthetic-cleanup-sitting"))
        cipher.assertWipedAndReset()
        check(runCatching { repository.record("synthetic-after-finish", "synthetic-cleanup-sitting", Eye.RIGHT, "13.4") }
            .exceptionOrNull() is IllegalArgumentException)
        cipher.assertWipedAndReset()
        check(database.readings().allReadings().single().id == "synthetic-cleanup-reading")
        check(database.readings().allSittings().size == 1)
    }

    @Test
    fun scopedHelperClearsForConsumerExceptionsCancellationAndErrors() {
        val aad = ReadingAad.draft()
        val sealed = cipher.seal(byteArrayOf(1, 2, 3), aad)
        listOf(IllegalStateException("Synthetic consumer failure"),
            CancellationException("Synthetic cancellation"), AssertionError("Synthetic consumer error")).forEach { failure ->
            check(runCatching {
                cipher.withDecryptedPayload(sealed, aad) { clear ->
                    check(clear.contentEquals(byteArrayOf(1, 2, 3)))
                    throw failure
                }
            }.exceptionOrNull() === failure)
            cipher.assertWipedAndReset()
        }
    }
}
