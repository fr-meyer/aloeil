package org.aloeil.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import javax.crypto.AEADBadTagException

@Entity(tableName = "readings")
data class ReadingRow(
    @PrimaryKey val id: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val revision: Long,
    val replicaConfirmedRevision: Long,
)

@Entity(tableName = "outbox", indices = [Index("readingId")])
data class OutboxRow(
    @PrimaryKey val id: String,
    val readingId: String,
    val revision: Long,
    val attemptCount: Int,
    val nextAttemptAtMillis: Long,
)

@Entity(tableName = "sittings")
data class SittingRow(
    @PrimaryKey val id: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

@Entity(tableName = "draft_checkpoint")
data class DraftRow(
    @PrimaryKey val id: Int = 1,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

@Entity(tableName = "reading_versions", primaryKeys = ["readingId", "revision"])
data class ReadingVersionRow(
    val readingId: String,
    val revision: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

@Entity(tableName = "deleted_readings")
data class DeletedReadingRow(
    @PrimaryKey val id: String,
)

@Entity(tableName = "correction_operations")
data class CorrectionOperationRow(
    @PrimaryKey val id: String,
    val readingId: String,
    val resultingRevision: Long,
)

data class ArchiveSnapshotRows(
    val readings: List<ReadingRow>,
    val sittings: List<SittingRow>,
    val versions: List<ReadingVersionRow>,
    val operations: List<CorrectionOperationRow>,
    val deleted: List<DeletedReadingRow>,
)

@Dao
interface ReadingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSitting(row: SittingRow)

    @Transaction
    suspend fun insertOpenSitting(row: SittingRow, cipher: ReadingCipher) {
        val open = allSittings().map { stored ->
            SittingPayloadCodec.decode(
                stored.id, cipher.open(SealedPayload(stored.nonce, stored.ciphertext), ReadingAad.sitting(stored.id)),
            )
        }.filter { it.finishedAtMillis == null }
        require(open.size <= 1) { "Multiple sittings are already open" }
        if (open.isNotEmpty()) {
            require(open.single().id == row.id) { "Another sitting is already open" }
            return
        }
        require(sitting(row.id) == null) { "Sitting ID was already used" }
        insertSitting(row)
    }

    /** Re-encrypt old rows in one Room transaction before removing the old Keystore key.
     * A crash before commit keeps every old row and key; a crash after commit can retry cleanup.
     */
    @Transaction
    suspend fun migrateLegacyEncryption(cipher: AndroidKeystoreReadingCipher) {
        fun upgraded(payload: SealedPayload, aad: ByteArray): SealedPayload? {
            try {
                cipher.open(payload, aad)
                return null
            } catch (_: AEADBadTagException) {
                // An old authenticated payload has no AAD. It is read only during migration.
            } catch (_: MissingReadingKeyException) {
                // The new key may not exist yet when opening an old database.
            }
            val clear = cipher.openLegacy(payload)
            return cipher.seal(clear, aad)
        }
        allReadings().forEach { row ->
            upgraded(
                SealedPayload(row.nonce, row.ciphertext),
                ReadingAad.reading(row.id, row.revision, row.replicaConfirmedRevision),
            )?.let { sealed ->
                require(updateReading(row.copy(nonce = sealed.nonce, ciphertext = sealed.ciphertext)) == 1)
            }
        }
        allSittings().forEach { row ->
            upgraded(SealedPayload(row.nonce, row.ciphertext), ReadingAad.sitting(row.id))
                ?.let { sealed ->
                    require(updateSitting(row.copy(nonce = sealed.nonce, ciphertext = sealed.ciphertext)) == 1)
                }
        }
        allVersions().forEach { row ->
            upgraded(
                SealedPayload(row.nonce, row.ciphertext),
                ReadingAad.version(row.readingId, row.revision),
            )?.let { sealed ->
                require(updateVersion(row.copy(nonce = sealed.nonce, ciphertext = sealed.ciphertext)) == 1)
            }
        }
        draft()?.let { row ->
            // Drafts are unsaved checkpoints. A damaged current-format draft must
            // not roll back migration or make valid saved readings unreadable.
            val sealed = try {
                upgraded(SealedPayload(row.nonce, row.ciphertext), ReadingAad.draft())
            } catch (_: AEADBadTagException) {
                null
            } catch (_: MissingReadingKeyException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            sealed?.let { saveDraft(row.copy(nonce = it.nonce, ciphertext = it.ciphertext)) }
        }
    }

    @Query("SELECT * FROM sittings")
    suspend fun allSittings(): List<SittingRow>

    @Query("SELECT * FROM sittings WHERE id = :id LIMIT 1")
    suspend fun sitting(id: String): SittingRow?

    @Update
    suspend fun updateSitting(row: SittingRow): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(row: DraftRow)

    @Query("SELECT * FROM draft_checkpoint WHERE id = 1 LIMIT 1")
    suspend fun draft(): DraftRow?

    @Query("DELETE FROM draft_checkpoint WHERE id = 1")
    suspend fun clearDraft()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReading(row: ReadingRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(row: OutboxRow)

    @Transaction
    suspend fun saveOnPhone(
        reading: ReadingRow,
        outbox: OutboxRow,
        sittingId: String,
        cipher: ReadingCipher,
    ): ReadingRow {
        require(deletedReading(reading.id) == null) { "Reading ID was deleted" }
        this.reading(reading.id)?.let { return it }
        val linked = sitting(sittingId) ?: throw IllegalArgumentException("Sitting does not exist")
        val state = SittingPayloadCodec.decode(
            linked.id, cipher.open(SealedPayload(linked.nonce, linked.ciphertext), ReadingAad.sitting(linked.id)),
        )
        require(state.finishedAtMillis == null) { "Sitting is already finished" }
        insertReading(reading)
        insertOutbox(outbox)
        return reading
    }

    @Transaction
    suspend fun archiveSnapshot(): ArchiveSnapshotRows = ArchiveSnapshotRows(
        allReadings(), allSittings(), allVersions(), allCorrectionOperations(), allDeletedReadings(),
    )

    @Query("SELECT * FROM readings ORDER BY id DESC")
    suspend fun allReadings(): List<ReadingRow>

    @Query("SELECT * FROM readings WHERE id = :id LIMIT 1")
    suspend fun reading(id: String): ReadingRow?

    @Query("SELECT * FROM deleted_readings WHERE id = :id LIMIT 1")
    suspend fun deletedReading(id: String): DeletedReadingRow?

    @Query("SELECT * FROM deleted_readings ORDER BY id")
    suspend fun allDeletedReadings(): List<DeletedReadingRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDeleted(row: DeletedReadingRow)

    @Query("DELETE FROM readings WHERE id = :id")
    suspend fun removeReading(id: String)

    @Query("DELETE FROM reading_versions WHERE readingId = :id")
    suspend fun removeVersions(id: String)

    @Query("DELETE FROM correction_operations WHERE readingId = :id")
    suspend fun removeOperations(id: String)

    @Transaction
    suspend fun deleteReading(id: String, expectedRevision: Long): Boolean {
        val current = reading(id) ?: return deletedReading(id) != null
        if (current.revision != expectedRevision) return false
        insertDeleted(DeletedReadingRow(id))
        removeStaleOutbox(id)
        removeVersions(id)
        removeOperations(id)
        removeReading(id)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(row: ReadingVersionRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCorrectionOperation(row: CorrectionOperationRow)

    @Update
    suspend fun updateVersion(row: ReadingVersionRow): Int

    @Query("SELECT * FROM reading_versions")
    suspend fun allVersions(): List<ReadingVersionRow>

    @Query("SELECT * FROM correction_operations")
    suspend fun allCorrectionOperations(): List<CorrectionOperationRow>

    @Query("SELECT * FROM reading_versions WHERE readingId = :id ORDER BY revision")
    suspend fun versionsForReading(id: String): List<ReadingVersionRow>

    @Query("SELECT * FROM correction_operations WHERE readingId = :id ORDER BY resultingRevision, id")
    suspend fun operationsForReading(id: String): List<CorrectionOperationRow>

    @Query("SELECT * FROM reading_versions WHERE readingId = :id AND revision = :revision LIMIT 1")
    suspend fun version(id: String, revision: Long): ReadingVersionRow?

    @Query("SELECT * FROM correction_operations WHERE id = :id LIMIT 1")
    suspend fun correctionOperation(id: String): CorrectionOperationRow?

    @Update
    suspend fun updateReading(row: ReadingRow): Int

    @Query("DELETE FROM outbox WHERE readingId = :readingId")
    suspend fun removeStaleOutbox(readingId: String)

    /** The old encrypted value is retained for persistent undo. */
    @Transaction
    suspend fun applyCorrection(
        operationId: String,
        expectedRevision: Long,
        updated: ReadingRow,
        priorVersion: ReadingVersionRow,
        outbox: OutboxRow,
    ): Boolean {
        correctionOperation(operationId)?.let {
            return it.readingId == updated.id && it.resultingRevision == expectedRevision + 1
        }
        val old = reading(updated.id) ?: return false
        if (old.revision != expectedRevision) return false
        require(updated.revision == old.revision + 1)
        require(priorVersion.readingId == old.id && priorVersion.revision == old.revision)
        insertVersion(priorVersion)
        require(updateReading(updated) == 1)
        removeStaleOutbox(old.id)
        insertOutbox(outbox)
        insertCorrectionOperation(CorrectionOperationRow(operationId, old.id, updated.revision))
        return true
    }

    /** Authenticate and prepare off-DB first; this transaction never overwrites a phone row. */
    @Transaction
    suspend fun restoreArchive(
        sittings: List<Pair<SittingRow, Sitting>>,
        rows: List<Pair<ReadingRow, OutboxRow>>,
        versions: List<ReadingVersionRow>,
        operations: List<CorrectionOperationRow>,
        expectedReadings: List<Reading>,
        tombstones: List<DeletedReadingRow>,
        cipher: ReadingCipher,
    ): Int {
        require(rows.size == expectedReadings.size)
        tombstones.forEach { incoming ->
            // Additive restore never deletes an active reading already on this phone.
            if (reading(incoming.id) != null) return@forEach
            val existing = deletedReading(incoming.id)
            if (existing == null) insertDeleted(incoming)
            else require(existing == incoming) { "Conflicting deleted reading ID" }
        }
        val archivedSittingIdsWithRows = expectedReadings.map { it.sittingId }.toSet()
        val survivingSittingIds = rows.indices.mapNotNull { index ->
            expectedReadings[index].sittingId.takeIf {
                deletedReading(rows[index].first.id) == null
            }
        }.toSet()
        val sittingsToRestore = sittings.filter { (incoming, _) ->
            sitting(incoming.id) != null ||
                incoming.id !in archivedSittingIdsWithRows ||
                incoming.id in survivingSittingIds
        }
        val localOpenIds = allSittings().map { row ->
            SittingPayloadCodec.decode(
                row.id, cipher.open(SealedPayload(row.nonce, row.ciphertext), ReadingAad.sitting(row.id)),
            )
        }.filter { it.finishedAtMillis == null }.map { it.id }.toSet()
        val newOpenIds = sittingsToRestore.filter { (row, expected) ->
            expected.finishedAtMillis == null && sitting(row.id) == null
        }.map { it.second.id }.toSet()
        require(newOpenIds.isEmpty() || (localOpenIds + newOpenIds).size == 1) {
            "Archive would create a second open sitting"
        }
        sittingsToRestore.forEach { (incoming, expected) ->
            val existing = sitting(incoming.id)
            if (existing == null) {
                insertSitting(incoming)
            } else {
                val decoded = SittingPayloadCodec.decode(
                    existing.id,
                    cipher.open(SealedPayload(existing.nonce, existing.ciphertext), ReadingAad.sitting(existing.id)),
                )
                // A sitting may have been finished after the backup was made (or vice versa).
                // Preserve this phone's state when the shared start is identical.
                require(decoded.id == expected.id &&
                    decoded.startedAtMillis == expected.startedAtMillis &&
                    (decoded.finishedAtMillis == expected.finishedAtMillis ||
                        decoded.finishedAtMillis == null || expected.finishedAtMillis == null)
                ) { "Conflicting sitting ID in archive" }
            }
        }
        val versionsByReading = versions.groupBy { it.readingId }
        val operationsByReading = operations.groupBy { it.readingId }
        rows.forEachIndexed { index, (incoming, _) ->
            if (deletedReading(incoming.id) != null) return@forEachIndexed
            val existing = reading(incoming.id) ?: return@forEachIndexed
            val expected = expectedReadings[index]
            val currentPayload = ReadingPayloadCodec.decode(
                cipher.open(SealedPayload(existing.nonce, existing.ciphertext),
                    ReadingAad.reading(existing.id, existing.revision, existing.replicaConfirmedRevision)),
            )
            val currentVersions = versionsForReading(incoming.id).map { row ->
                row.revision to ReadingPayloadCodec.decode(
                    cipher.open(SealedPayload(row.nonce, row.ciphertext),
                        ReadingAad.version(row.readingId, row.revision)),
                )
            }
            val incomingVersions = versionsByReading[incoming.id].orEmpty()
                .sortedBy { it.revision }.map { row ->
                    row.revision to ReadingPayloadCodec.decode(
                        cipher.open(SealedPayload(row.nonce, row.ciphertext),
                            ReadingAad.version(row.readingId, row.revision)),
                    )
                }
            val sharedRevision = minOf(existing.revision, expected.revision)
            val localSharedHistory =
                (currentVersions + (existing.revision to currentPayload))
                    .filter { it.first <= sharedRevision }
            val archivedSharedHistory =
                (incomingVersions + (expected.revision to expected.asPayload()))
                    .filter { it.first <= sharedRevision }
            require(localSharedHistory == archivedSharedHistory &&
                localSharedHistory.size.toLong() == sharedRevision
            ) { "Conflicting reading history in archive" }
            val incomingOperations = operationsByReading[incoming.id].orEmpty()
                .sortedWith(compareBy<CorrectionOperationRow> { it.resultingRevision }.thenBy { it.id })
            require(
                operationsForReading(incoming.id).filter {
                    it.resultingRevision <= sharedRevision
                } == incomingOperations.filter {
                    it.resultingRevision <= sharedRevision
                },
            ) { "Conflicting correction history in archive" }
            // Compatible revisions already have this phone's ID; keep its current state.
        }
        var added = 0
        for ((incoming, outbox) in rows) {
            if (reading(incoming.id) == null && deletedReading(incoming.id) == null) {
                insertReading(incoming)
                insertOutbox(outbox)
                versionsByReading[incoming.id].orEmpty().forEach { insertVersion(it) }
                operationsByReading[incoming.id].orEmpty().forEach { insertCorrectionOperation(it) }
                added++
            }
        }
        return added
    }

    @Query("SELECT * FROM outbox WHERE nextAttemptAtMillis <= :now ORDER BY nextAttemptAtMillis, id")
    suspend fun dueOutbox(now: Long): List<OutboxRow>

    @Query("UPDATE outbox SET attemptCount = attemptCount + 1, nextAttemptAtMillis = :retryAt WHERE id = :id")
    suspend fun retryLater(id: String, retryAt: Long)

    @Query("DELETE FROM outbox WHERE readingId = :id AND revision = :revision")
    suspend fun removeConfirmedOutbox(id: String, revision: Long)

    @Transaction
    suspend fun acknowledgeReplica(id: String, revision: Long, cipher: ReadingCipher): Boolean {
        val current = reading(id) ?: return false
        if (current.revision != revision) return false
        val clear = cipher.open(
            SealedPayload(current.nonce, current.ciphertext),
            ReadingAad.reading(id, revision, current.replicaConfirmedRevision),
        )
        val sealed = cipher.seal(clear, ReadingAad.reading(id, revision, revision))
        if (updateReading(current.copy(
                nonce = sealed.nonce, ciphertext = sealed.ciphertext,
                replicaConfirmedRevision = revision,
            )) != 1) return false
        removeConfirmedOutbox(id, revision)
        return true
    }
}

@Database(
    entities = [
        ReadingRow::class, OutboxRow::class, SittingRow::class, DraftRow::class,
        ReadingVersionRow::class, CorrectionOperationRow::class, DeletedReadingRow::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_readings` (" +
                        "`id` TEXT NOT NULL, `deletedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_readings_new` (" +
                        "`id` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `deleted_readings_new` (`id`) " +
                        "SELECT `id` FROM `deleted_readings`",
                )
                db.execSQL("DROP TABLE `deleted_readings`")
                db.execSQL("ALTER TABLE `deleted_readings_new` RENAME TO `deleted_readings`")
            }
        }

        private const val DATABASE_NAME = "aloeil-readings.db"
        @Volatile private var applicationInstance: ReadingDatabase? = null

        fun open(context: Context): ReadingDatabase =
            applicationInstance ?: synchronized(this) {
                applicationInstance ?: Room.databaseBuilder(
                    context.applicationContext, ReadingDatabase::class.java, DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { applicationInstance = it }
            }

        /** The caller must obtain explicit confirmation before invoking this destructive reset. */
        fun resetUnreadableStore(context: Context) = synchronized(this) {
            applicationInstance?.close()
            applicationInstance = null
            val file = context.applicationContext.getDatabasePath(DATABASE_NAME)
            if (file.exists()) require(SQLiteDatabase.deleteDatabase(file)) {
                "Unreadable local database could not be deleted"
            }
        }
    }
}
