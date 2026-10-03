package org.aloeil.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aloeil.app.data.DeletedReadingRow
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDao
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.SittingRow
import org.junit.Test
import org.junit.runner.RunWith

/** A recovery decision about an old row must never delete a newer unsaved row. */
@RunWith(AndroidJUnit4::class)
class DraftRecoveryRaceDeviceTest {
    @Test
    fun closedSittingCleanupKeepsConcurrentlySavedCheckpoint() = exerciseRace(false)

    @Test
    fun deletedReadingCleanupKeepsConcurrentlySavedCheckpoint() = exerciseRace(true)

    private fun exerciseRace(deletedReading: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            runBlocking {
                val dao = database.readings()
                val cipher = SyntheticCipher()
                val writer = ReadingRepository(dao, cipher, { "UTC" }) { 1_700_000_000_000L }
                writer.startSitting("synthetic-old-sitting")
                if (deletedReading) {
                    writer.record("synthetic-old-reading", "synthetic-old-sitting", Eye.LEFT, "12.3")
                    check(writer.deleteReading("synthetic-old-reading", 1))
                }
                check(writer.finishSitting("synthetic-old-sitting"))
                writer.startSitting("synthetic-new-sitting")
                writer.saveDraft(DraftCheckpoint(
                    sittingId = "synthetic-old-sitting", readingId = "synthetic-old-reading",
                    step = "VALUE", eye = Eye.LEFT, input = "12.3", focusedControl = "reading",
                    fromHistory = deletedReading,
                ))
                val heldOnce = AtomicBoolean(false)
                val gate = object : ReadingDao by dao {
                    override suspend fun sitting(id: String): SittingRow? {
                        val row = dao.sitting(id)
                        if (!deletedReading && id == "synthetic-old-sitting" && heldOnce.compareAndSet(false, true)) {
                            entered.complete(Unit)
                            withTimeout(10_000) { release.await() }
                        }
                        return row
                    }

                    override suspend fun deletedReading(id: String): DeletedReadingRow? {
                        val row = dao.deletedReading(id)
                        if (deletedReading && id == "synthetic-old-reading" && heldOnce.compareAndSet(false, true)) {
                            entered.complete(Unit)
                            withTimeout(10_000) { release.await() }
                        }
                        return row
                    }
                }
                val recovering = ReadingRepository(gate, cipher, { "UTC" }) { 1_700_000_000_000L }
                val recovery = async(Dispatchers.Default) { recovering.recoverDraft() }
                try {
                    withTimeout(10_000) { entered.await() }
                    val newer = DraftCheckpoint(
                        sittingId = "synthetic-new-sitting", readingId = "synthetic-new-reading",
                        step = "VALUE", eye = Eye.RIGHT, input = "14.2", focusedControl = "reading",
                    )
                    writer.saveDraft(newer)
                    val checkpoint = checkNotNull(dao.draft())
                    release.complete(Unit)
                    val recovered = checkNotNull(withTimeout(10_000) { recovery.await() })
                    check(recovered.first == newer && recovered.second == null)
                    val retained = checkNotNull(dao.draft())
                    check(retained.nonce.contentEquals(checkpoint.nonce))
                    check(retained.ciphertext.contentEquals(checkpoint.ciphertext))
                    check(writer.openSitting()?.id == "synthetic-new-sitting")
                    check(writer.all().isEmpty())
                } finally {
                    release.complete(Unit)
                }
            }
        } finally {
            release.complete(Unit)
            database.close()
        }
    }
}
