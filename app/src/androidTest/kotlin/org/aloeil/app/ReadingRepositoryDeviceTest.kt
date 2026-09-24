package org.aloeil.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.ArchiveBundle
import org.aloeil.app.data.ArchiveCodec
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.Eye
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingCipher
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.RangeState
import org.aloeil.app.data.SealedPayload
import org.aloeil.app.data.Sitting
import org.aloeil.app.data.restoredDraftStep
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs with synthetic values on an Android device or emulator. */
@RunWith(AndroidJUnit4::class)
class ReadingRepositoryDeviceTest {
    private lateinit var context: Context
    private lateinit var db: ReadingDatabase
    private lateinit var cipher: ReadingCipher
    private val time = 1_700_000_000_000L
    private val passphrase = "synthetic-device-test-only".toCharArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        cipher = SyntheticCipher()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(database: ReadingDatabase = db): ReadingRepository =
        ReadingRepository(database.readings(), cipher) { time }

    @Test
    fun saveAndRetryKeepOneReadingAndOneOutboxRow() = runBlocking {
        val repo = repository()
        repo.startSitting("synthetic-sitting")
        val first = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val retried = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        check(first == retried)
        check(repo.all().size == 1)
        check(repo.dueForReplica().size == 1)
        check(runCatching {
            repo.record("synthetic-reading", "synthetic-sitting", Eye.RIGHT, "14.1")
        }.isFailure)
        check(repo.all().single() == first)
    }

    @Test
    fun correctionRetryConflictAndUndoRetainHistory() = runBlocking {
        val repo = repository()
        repo.startSitting("synthetic-sitting")
        val original = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val corrected = repo.correct("synthetic-correction-1", original.id, 1, Eye.RIGHT, "13.1")!!
        check(corrected.revision == 2L)
        check(repo.correct("synthetic-correction-1", original.id, 1, Eye.RIGHT, "13.1") == corrected)
        check(repo.correct("synthetic-stale", original.id, 1, Eye.RIGHT, "15.0") == null)
        val undone = repo.undoCorrection("synthetic-undo", original.id, 2)!!
        check(undone.revision == 3L && undone.eye == Eye.LEFT && undone.value == "12.3")
        check(repo.correct("synthetic-correction-1", original.id, 1, Eye.RIGHT, "13.1") == null)
        check(db.readings().versionsForReading(original.id).map { it.revision } == listOf(1L, 2L))
        check(db.readings().operationsForReading(original.id).size == 2)
        check(repo.dueForReplica().single().revision == 3L)
    }

    @Test
    fun conflictingSittingRollsBackAndValidRestoreIsAdditive() = runBlocking {
        val repo = repository()
        repo.startSitting("synthetic-sitting")
        val original = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val newSitting = Sitting("synthetic-other-sitting", time + 100, null)
        val newReading = Reading(
            "synthetic-other-reading", newSitting.id, time + 101, Eye.RIGHT, "14.2", 1, 0,
        )
        val conflict = ArchiveBundle(
            readings = listOf(newReading),
            sittings = listOf(newSitting, Sitting("synthetic-sitting", time + 999, null)),
            versions = emptyList(),
            operations = emptyList(),
        )
        check(runCatching {
            repo.importArchive(ArchiveCodec.encode(conflict, passphrase), passphrase)
        }.isFailure)
        check(db.readings().sitting(newSitting.id) == null)
        check(repo.all() == listOf(original))
        val valid = conflict.copy(sittings = listOf(newSitting))
        val archive = ArchiveCodec.encode(valid, passphrase)
        check(repo.importArchive(archive, passphrase) == 1)
        check(repo.importArchive(archive, passphrase) == 0)
        check(repo.all().size == 2)
        check(repo.dueForReplica().size == 2)
    }

    @Test
    fun conflictingReadingRollsBackNewSitting() = runBlocking {
        val repo = repository()
        repo.startSitting("synthetic-sitting")
        val original = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val newSitting = Sitting("synthetic-other-sitting", time + 100, null)
        val conflict = ArchiveBundle(
            readings = listOf(original.copy(value = "15.0")),
            sittings = listOf(
                Sitting("synthetic-sitting", time, null),
                newSitting,
            ),
            versions = emptyList(),
            operations = emptyList(),
        )
        check(runCatching {
            repo.importArchive(ArchiveCodec.encode(conflict, passphrase), passphrase)
        }.isFailure)
        check(db.readings().sitting(newSitting.id) == null)
        check(repo.all() == listOf(original))
    }

    @Test
    fun correctedArchiveRestoresUndoHistory() = runBlocking {
        val source = repository()
        source.startSitting("synthetic-sitting")
        val original = source.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        source.correct("synthetic-correction-1", original.id, 1, Eye.RIGHT, "13.1")
        val archive = source.exportArchive(passphrase)
        val target = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val restored = repository(target)
            check(restored.importArchive(archive, passphrase) == 1)
            val undone = restored.undoCorrection("synthetic-undo", original.id, 2)!!
            check(undone.revision == 3L && undone.eye == Eye.LEFT && undone.value == "12.3")
        } finally {
            target.close()
        }
    }

    @Test
    fun rangeNoteZoneAndUndoSurviveEncryptedBackup() = runBlocking {
        val source = ReadingRepository(db.readings(), cipher, { "Asia/Seoul" }, { time })
        source.startSitting("synthetic-sitting")
        val original = source.recordRange(
            "synthetic-range", "synthetic-sitting", Eye.LEFT,
            RangeState.ABOVE_RANGE, "Synthetic note",
        )
        check(original.value.isEmpty())
        check(original.timeZoneId == "Asia/Seoul")
        check(original.createdAtMillis == time && original.updatedAtMillis == time)
        val noted = source.correctNote("synthetic-note", original.id, 1, "Updated note")!!
        check(noted.note == "Updated note" && noted.rangeState == RangeState.ABOVE_RANGE)
        val changed = source.correct("synthetic-number", original.id, 2, Eye.LEFT, "12.3")!!
        check(changed.rangeState == null && changed.value == "12.3")
        val archive = source.exportArchive(passphrase)
        val target = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val restored = ReadingRepository(target.readings(), cipher, { "UTC" }, { time })
            check(restored.importArchive(archive, passphrase) == 1)
            val current = restored.all().single()
            check(current == changed.copy(replicaConfirmedRevision = 0))
            val undone = restored.undoCorrection("synthetic-undo", original.id, 3)!!
            check(undone.rangeState == RangeState.ABOVE_RANGE)
            check(undone.value.isEmpty() && undone.note == "Updated note")
            check(undone.timeZoneId == "Asia/Seoul")
        } finally {
            target.close()
        }
    }

    @Test
    fun backwardClockCannotMakeLocalSittingUnexportable() = runBlocking {
        var clock = time
        val repo = ReadingRepository(db.readings(), cipher) { clock }
        repo.startSitting("synthetic-sitting")
        repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        clock = time - 60_000
        check(repo.finishSitting("synthetic-sitting"))
        val archive = repo.exportArchive(passphrase)
        val sitting = ArchiveCodec.decode(archive, passphrase).sittings.single()
        check(sitting.finishedAtMillis == sitting.startedAtMillis)
    }

    @Test
    fun fileDatabaseRestartRecoversCommittedReadingAndAbandonedCorrection() = runBlocking {
        val name = "synthetic-restart-" + UUID.randomUUID() + ".db"
        val first = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val repo = repository(first)
            repo.startSitting("synthetic-sitting")
            val saved = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
            repo.saveDraft(DraftCheckpoint(
                saved.sittingId, saved.id, "CORRECT_CHOICE", saved.eye, saved.value,
                "heading", baseRevision = saved.revision,
            ))
            // The Back action writes this terminal checkpoint before changing the UI.
            repo.saveDraft(DraftCheckpoint(
                saved.sittingId, saved.id, "SAVED", saved.eye, saved.value, "heading",
            ))
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val recovered = repository(reopened).recoverDraft()!!
            check(recovered.second != null)
            check(restoredDraftStep(recovered.first, recovered.second) == "SAVED")
            check(reopened.readings().allReadings().size == 1)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}

private class SyntheticCipher : ReadingCipher {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val random = SecureRandom()

    override fun seal(plaintext: ByteArray): SealedPayload {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return SealedPayload(nonce, cipher.doFinal(plaintext))
    }

    override fun open(payload: SealedPayload): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.nonce))
        return cipher.doFinal(payload.ciphertext)
    }
}
