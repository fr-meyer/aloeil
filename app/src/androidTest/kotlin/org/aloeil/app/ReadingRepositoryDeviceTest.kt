package org.aloeil.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        ReadingRepository(database.readings(), cipher, { "UTC" }, { time })

    @Test
    fun noteLimitIsEnforcedBeforeSaveAndCorrection() = runBlocking {
        val repo = repository()
        repo.startSitting("synthetic-sitting")
        val fullNote = "n".repeat(1000)
        val saved = repo.record(
            "synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3", fullNote,
        )
        check(saved.note == fullNote)
        check(runCatching {
            repo.record(
                "synthetic-too-long", "synthetic-sitting", Eye.RIGHT, "13.4",
                "n".repeat(1001),
            )
        }.isFailure)
        check(repo.all().size == 1)

        val corrected = repo.correctNote(
            "synthetic-note-correction", saved.id, saved.revision, fullNote,
        )!!
        check(corrected.revision == 2L && corrected.note == fullNote)
        check(runCatching {
            repo.correctNote(
                "synthetic-too-long-correction", saved.id, corrected.revision,
                "n".repeat(1001),
            )
        }.isFailure)
        check(repo.all().single() == corrected)
    }

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
    fun simultaneousStartsKeepExactlyOneOpenSitting() = runBlocking {
        val repo = repository()
        val attempts = listOf("synthetic-first", "synthetic-second").map { id ->
            async(Dispatchers.Default) { runCatching { repo.startSitting(id) }.getOrNull() }
        }.awaitAll()
        val winner = attempts.filterNotNull().single()
        check(repo.startSitting(winner) == winner)
        check(runCatching { repo.startSitting("synthetic-third") }.isFailure)
        check(repo.allSittings().single().id == winner)
        check(repo.openSitting()?.id == winner)
    }

    @Test
    fun newReadingRequiresExistingOpenSitting() = runBlocking {
        val repo = repository()
        check(runCatching {
            repo.record("synthetic-orphan", "missing-sitting", Eye.LEFT, "12.3")
        }.isFailure)
        check(repo.all().isEmpty() && repo.dueForReplica().isEmpty())

        repo.startSitting("synthetic-sitting")
        val saved = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        check(repo.finishSitting("synthetic-sitting"))
        check(runCatching {
            repo.record("synthetic-late", "synthetic-sitting", Eye.RIGHT, "14.2")
        }.isFailure)
        check(repo.record(saved.id, saved.sittingId, saved.eye, saved.value) == saved)
        check(repo.all() == listOf(saved))
        check(repo.dueForReplica().single().readingId == saved.id)
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
        val newSitting = Sitting("synthetic-other-sitting", time + 100, time + 200)
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
    fun importedOpenSittingCannotDisplaceLocalCaptureEvenWhenReadingWasDeleted() = runBlocking {
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val archive = try {
            val source = ReadingRepository(sourceDb.readings(), cipher, { "UTC" }) { time + 1000 }
            source.startSitting("imported-sitting")
            source.record("shared-reading", "imported-sitting", Eye.LEFT, "12.3")
            source.exportArchive(passphrase)
        } finally {
            sourceDb.close()
        }
        val local = repository()
        local.startSitting("local-sitting")
        val old = local.record("shared-reading", "local-sitting", Eye.RIGHT, "14.2")
        check(local.deleteReading(old.id, old.revision))
        check(local.importArchive(archive, passphrase) == 0)
        check(local.openSitting()?.id == "local-sitting")
        check(local.allSittings().map { it.id } == listOf("local-sitting"))
        check(local.all().isEmpty())
        check(db.readings().deletedReading("shared-reading") != null)
        check(local.finishSitting("local-sitting"))
        check(local.importArchive(archive, passphrase) == 0)
        check(local.openSitting() == null)
        check(local.allSittings().map { it.id } == listOf("local-sitting"))
    }

    @Test
    fun incomingTombstonePreservesActiveReadingAndRestoresUnrelatedReading() = runBlocking {
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val archive = try {
            val source = ReadingRepository(sourceDb.readings(), cipher, { "UTC" }) { time + 1000 }
            source.startSitting("archive-sitting")
            val removed = source.record("shared-reading", "archive-sitting", Eye.LEFT, "12.3")
            check(source.deleteReading(removed.id, removed.revision))
            source.record("unrelated-reading", "archive-sitting", Eye.RIGHT, "15.4")
            check(source.finishSitting("archive-sitting"))
            source.exportArchive(passphrase)
        } finally {
            sourceDb.close()
        }
        val local = repository()
        local.startSitting("local-sitting")
        val retained = local.record("shared-reading", "local-sitting", Eye.RIGHT, "14.2")
        check(local.finishSitting("local-sitting"))

        check(local.importArchive(archive, passphrase) == 1)
        check(local.importArchive(archive, passphrase) == 0)
        val restored = local.all().associateBy { it.id }
        check(restored["shared-reading"] == retained)
        check(restored["unrelated-reading"]?.value == "15.4")
        check(db.readings().deletedReading("shared-reading") == null)
        check(local.allSittings().map { it.id }.toSet() == setOf("local-sitting", "archive-sitting"))
    }

    @Test
    fun importedOpenSittingWithSurvivingReadingCannotDisplaceLocalCapture() = runBlocking {
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val archive = try {
            val source = ReadingRepository(sourceDb.readings(), cipher, { "UTC" }) { time + 1000 }
            source.startSitting("imported-sitting")
            source.record("imported-reading", "imported-sitting", Eye.LEFT, "12.3")
            source.exportArchive(passphrase)
        } finally {
            sourceDb.close()
        }
        val local = repository()
        local.startSitting("local-sitting")
        val localReading = local.record("local-reading", "local-sitting", Eye.RIGHT, "14.2")
        check(runCatching { local.importArchive(archive, passphrase) }.isFailure)
        check(local.openSitting()?.id == "local-sitting")
        check(local.allSittings().map { it.id } == listOf("local-sitting"))
        check(local.all() == listOf(localReading))
    }

    @Test
    fun conflictingReadingRollsBackNewSitting() = runBlocking {
        val repo = repository()
        repo.startSitting("synthetic-sitting")
        val original = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val newSitting = Sitting("synthetic-other-sitting", time + 100, time + 200)
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
    fun olderCompatibleArchiveAddsMissingReadingWithoutRollingBackLocalCorrection() = runBlocking {
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val archive = try {
            val source = repository(sourceDb)
            source.startSitting("shared-sitting")
            source.record("shared-reading", "shared-sitting", Eye.LEFT, "12.3")
            source.record("missing-reading", "shared-sitting", Eye.RIGHT, "15.4")
            source.exportArchive(passphrase)
        } finally {
            sourceDb.close()
        }
        val local = repository()
        local.startSitting("shared-sitting")
        val original = local.record("shared-reading", "shared-sitting", Eye.LEFT, "12.3")
        val corrected = local.correct(
            "local-correction", original.id, original.revision, Eye.RIGHT, "14.2",
        )!!
        check(local.finishSitting("shared-sitting"))

        check(local.importArchive(archive, passphrase) == 1)
        check(local.importArchive(archive, passphrase) == 0)
        val readings = local.all().associateBy { it.id }
        check(readings["shared-reading"] == corrected)
        check(readings["missing-reading"]?.value == "15.4")
        check(local.openSitting() == null)
        check(db.readings().operationsForReading(original.id).single().id == "local-correction")
    }

    @Test
    fun newerCompatibleArchiveAddsMissingReadingWithoutOverwritingPhone() = runBlocking {
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val archive = try {
            val source = repository(sourceDb)
            source.startSitting("shared-sitting")
            val original = source.record("shared-reading", "shared-sitting", Eye.LEFT, "12.3")
            source.record("missing-reading", "shared-sitting", Eye.RIGHT, "15.4")
            source.correct("archived-correction", original.id, 1, Eye.RIGHT, "14.2")
            source.exportArchive(passphrase)
        } finally {
            sourceDb.close()
        }
        val local = repository()
        local.startSitting("shared-sitting")
        val retained = local.record("shared-reading", "shared-sitting", Eye.LEFT, "12.3")

        check(local.importArchive(archive, passphrase) == 1)
        val readings = local.all().associateBy { it.id }
        check(readings["shared-reading"] == retained)
        check(readings["missing-reading"]?.value == "15.4")
        check(db.readings().operationsForReading(retained.id).isEmpty())
    }

    @Test
    fun deletionErasesContentAndOlderBackupCannotResurrectIt() = runBlocking {
        val repo = repository()
        repo.startSitting("synthetic-sitting")
        val original = repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val corrected = repo.correct("synthetic-correction", original.id, 1, Eye.RIGHT, "14.2")!!
        val oldBackup = repo.exportArchive(passphrase)
        check(!repo.deleteReading(corrected.id, 1))
        check(repo.all().single() == corrected)
        check(repo.deleteReading(corrected.id, 2))
        check(repo.all().isEmpty())
        check(repo.dueForReplica().isEmpty())
        check(db.readings().versionsForReading(original.id).isEmpty())
        check(db.readings().operationsForReading(original.id).isEmpty())
        check(db.readings().deletedReading(original.id) != null)
        check(repo.importArchive(oldBackup, passphrase) == 0)
        check(repo.all().isEmpty())
        val newBackup = repo.exportArchive(passphrase)
        check(ArchiveCodec.decode(newBackup, passphrase).deleted.single().id == original.id)
        val target = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val restored = repository(target)
            check(restored.importArchive(newBackup, passphrase) == 0)
            check(restored.importArchive(oldBackup, passphrase) == 0)
            check(restored.all().isEmpty())
        } finally {
            target.close()
        }
    }

    @Test
    fun freshFileDatabaseRestoresWithDifferentLocalKey() = runBlocking {
        val source = repository()
        source.startSitting("synthetic-sitting")
        val original = source.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val corrected = source.correct("synthetic-correction", original.id, 1, Eye.RIGHT, "14.2")!!
        val archive = source.exportArchive(passphrase)
        val name = "synthetic-fresh-profile-" + UUID.randomUUID() + ".db"
        val targetCipher = SyntheticCipher(8)
        val fresh = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val restored = ReadingRepository(fresh.readings(), targetCipher, { "UTC" }, { time })
            check(restored.all().isEmpty())
            check(restored.importArchive(archive, passphrase) == 1)
            check(restored.all().single() == corrected)
        } finally {
            fresh.close()
        }
        val reopened = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val restored = ReadingRepository(reopened.readings(), targetCipher, { "UTC" }, { time })
            check(restored.all().single() == corrected)
            val undone = restored.undoCorrection("synthetic-undo", original.id, 2)!!
            check(undone.eye == Eye.LEFT && undone.value == original.value)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionOneDatabaseMigratesWithoutLosingSyntheticRows() = runBlocking {
        val name = "synthetic-migration-" + UUID.randomUUID() + ".db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `readings` (`id` TEXT NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, `revision` INTEGER NOT NULL, `replicaConfirmedRevision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `readingId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_readingId` ON `outbox` (`readingId`)")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `sittings` (`id` TEXT NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `draft_checkpoint` (`id` INTEGER NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `reading_versions` (`readingId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, PRIMARY KEY(`readingId`, `revision`))")
            legacy.execSQL("CREATE TABLE IF NOT EXISTS `correction_operations` (`id` TEXT NOT NULL, `readingId` TEXT NOT NULL, `resultingRevision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL(
                "INSERT INTO `readings` VALUES (?, ?, ?, ?, ?)",
                arrayOf("synthetic-reading", byteArrayOf(1, 2), byteArrayOf(3, 4), 2L, 0L),
            )
            legacy.execSQL(
                "INSERT INTO `reading_versions` VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-reading", 1L, byteArrayOf(5), byteArrayOf(6)),
            )
            legacy.execSQL(
                "INSERT INTO `correction_operations` VALUES (?, ?, ?)",
                arrayOf("synthetic-operation", "synthetic-reading", 2L),
            )
            legacy.version = 1
        } finally {
            legacy.close()
        }
        val migrated = Room.databaseBuilder(context, ReadingDatabase::class.java, name)
            .addMigrations(ReadingDatabase.MIGRATION_1_2, ReadingDatabase.MIGRATION_2_3).build()
        try {
            val reading = migrated.readings().allReadings().single()
            check(reading.id == "synthetic-reading" && reading.revision == 2L)
            check(reading.ciphertext.contentEquals(byteArrayOf(3, 4)))
            check(migrated.readings().allVersions().single().ciphertext.contentEquals(byteArrayOf(6)))
            check(migrated.readings().allCorrectionOperations().single().id == "synthetic-operation")
            check(migrated.readings().allDeletedReadings().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionTwoMigrationRemovesPlaintextDeletionTime() = runBlocking {
        val name = "synthetic-tombstone-migration-" + UUID.randomUUID() + ".db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            legacy.execSQL("CREATE TABLE `readings` (`id` TEXT NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, `revision` INTEGER NOT NULL, `replicaConfirmedRevision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE `outbox` (`id` TEXT NOT NULL, `readingId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE INDEX `index_outbox_readingId` ON `outbox` (`readingId`)")
            legacy.execSQL("CREATE TABLE `sittings` (`id` TEXT NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE `draft_checkpoint` (`id` INTEGER NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE `reading_versions` (`readingId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, PRIMARY KEY(`readingId`, `revision`))")
            legacy.execSQL("CREATE TABLE `correction_operations` (`id` TEXT NOT NULL, `readingId` TEXT NOT NULL, `resultingRevision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL("CREATE TABLE `deleted_readings` (`id` TEXT NOT NULL, `deletedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            legacy.execSQL(
                "INSERT INTO `deleted_readings` VALUES (?, ?)",
                arrayOf("synthetic-deleted", time),
            )
            legacy.version = 2
        } finally {
            legacy.close()
        }
        val migrated = Room.databaseBuilder(context, ReadingDatabase::class.java, name)
            .addMigrations(ReadingDatabase.MIGRATION_2_3).build()
        try {
            check(migrated.readings().allDeletedReadings().single().id == "synthetic-deleted")
            val columns = migrated.openHelper.readableDatabase
                .query("PRAGMA table_info(`deleted_readings`)").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    }
                }
            check(columns == listOf("id"))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun backwardClockCannotMakeLocalSittingUnexportable() = runBlocking {
        var clock = time
        val repo = ReadingRepository(db.readings(), cipher, { "UTC" }, { clock })
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

internal class SyntheticCipher(keyByte: Byte = 7) : ReadingCipher {
    private val key = SecretKeySpec(ByteArray(32) { keyByte }, "AES")
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
