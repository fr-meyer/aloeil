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

@Entity(tableName = "readings", indices = [Index("sittingId"), Index("recordedAtMillis")])
data class ReadingRow(
    @PrimaryKey val id: String,
    val sittingId: String,
    val recordedAtMillis: Long,
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

@Dao
interface ReadingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReading(row: ReadingRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(row: OutboxRow)

    @Transaction
    suspend fun saveOnPhone(reading: ReadingRow, outbox: OutboxRow): ReadingRow {
        this.reading(reading.id)?.let { return it }
        insertReading(reading)
        insertOutbox(outbox)
        return reading
    }

    @Query("SELECT * FROM readings ORDER BY recordedAtMillis DESC, id DESC")
    suspend fun allReadings(): List<ReadingRow>

    @Query("SELECT * FROM readings WHERE id = :id LIMIT 1")
    suspend fun reading(id: String): ReadingRow?

    /** Restore is additive and atomic: an absent or empty replica never removes phone rows. */
    @Transaction
    suspend fun restoreMissing(rows: List<Pair<ReadingRow, OutboxRow>>): Int {
        var added = 0
        for ((incoming, outbox) in rows) {
            if (reading(incoming.id) == null) {
                insertReading(incoming)
                insertOutbox(outbox)
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

@Database(entities = [ReadingRow::class, OutboxRow::class], version = 1, exportSchema = true)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        fun open(context: Context): ReadingDatabase =
            Room.databaseBuilder(context.applicationContext, ReadingDatabase::class.java, "aloeil-readings.db")
                .build()
    }
}
