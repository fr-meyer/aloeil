package org.aloeil.app.data

import android.content.Context
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
    val deletedAtMillis: Long,
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
            linked.id, cipher.open(SealedPayload(linked.nonce, linked.ciphertext)),
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
    suspend fun deleteReading(id: String, expectedRevision: Long, deletedAtMillis: Long): Boolean {
        val current = reading(id) ?: return deletedReading(id) != null
        if (current.revision != expectedRevision) return false
        insertDeleted(DeletedReadingRow(id, deletedAtMillis))
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
        outbox: OutboxRow,
    ): Boolean {
        correctionOperation(operationId)?.let {
            return it.readingId == updated.id && it.resultingRevision == expectedRevision + 1
        }
        val old = reading(updated.id) ?: return false
        if (old.revision != expectedRevision) return false
        require(updated.revision == old.revision + 1)
        insertVersion(ReadingVersionRow(old.id, old.revision, old.nonce, old.ciphertext))
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
        tombstones.forEach { incoming ->
            require(reading(incoming.id) == null) { "Deleted archive ID conflicts with phone reading" }
            val existing = deletedReading(incoming.id)
            if (existing == null) insertDeleted(incoming)
            else require(existing == incoming) { "Conflicting deleted reading ID" }
        }
        sittings.forEach { (incoming, expected) ->
            val existing = sitting(incoming.id)
            if (existing == null) {
                insertSitting(incoming)
            } else {
                val decoded = SittingPayloadCodec.decode(
                    existing.id,
                    cipher.open(SealedPayload(existing.nonce, existing.ciphertext)),
                )
                require(decoded == expected) { "Conflicting sitting ID in archive" }
            }
        }
        val versionsByReading = versions.groupBy { it.readingId }
        val operationsByReading = operations.groupBy { it.readingId }
        require(rows.size == expectedReadings.size)
        rows.forEachIndexed { index, (incoming, _) ->
            if (deletedReading(incoming.id) != null) return@forEachIndexed
            val existing = reading(incoming.id) ?: return@forEachIndexed
            val expected = expectedReadings[index]
            val currentPayload = ReadingPayloadCodec.decode(
                cipher.open(SealedPayload(existing.nonce, existing.ciphertext)),
            )
            require(existing.revision == expected.revision && currentPayload == expected.asPayload()) {
                "Conflicting reading ID in archive"
            }
            val currentVersions = versionsForReading(incoming.id).map { row ->
                row.revision to ReadingPayloadCodec.decode(
                    cipher.open(SealedPayload(row.nonce, row.ciphertext)),
                )
            }
            val incomingVersions = versionsByReading[incoming.id].orEmpty()
                .sortedBy { it.revision }.map { row ->
                row.revision to ReadingPayloadCodec.decode(
                    cipher.open(SealedPayload(row.nonce, row.ciphertext)),
                )
            }
            require(currentVersions == incomingVersions) {
                "Conflicting reading history in archive"
            }
            val incomingOperations = operationsByReading[incoming.id].orEmpty()
                .sortedWith(compareBy<CorrectionOperationRow> { it.resultingRevision }.thenBy { it.id })
            require(operationsForReading(incoming.id) == incomingOperations) {
                "Conflicting correction history in archive"
            }
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

    @Query("UPDATE readings SET replicaConfirmedRevision = :revision WHERE id = :id AND revision = :revision")
    suspend fun confirmRevision(id: String, revision: Long): Int

    @Query("DELETE FROM outbox WHERE readingId = :id AND revision = :revision")
    suspend fun removeConfirmedOutbox(id: String, revision: Long)

    @Transaction
    suspend fun acknowledgeReplica(id: String, revision: Long): Boolean {
        if (confirmRevision(id, revision) != 1) return false
        removeConfirmedOutbox(id, revision)
        return true
    }
}

@Database(
    entities = [
        ReadingRow::class, OutboxRow::class, SittingRow::class, DraftRow::class,
        ReadingVersionRow::class, CorrectionOperationRow::class, DeletedReadingRow::class,
    ],
    version = 2,
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

        fun open(context: Context): ReadingDatabase =
            Room.databaseBuilder(context.applicationContext, ReadingDatabase::class.java, "aloeil-readings.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
