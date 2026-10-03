package org.aloeil.app

import android.content.Context
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.ArchiveSnapshotRows
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.Eye
import org.aloeil.app.data.MissingReadingKeyException
import org.aloeil.app.data.ReadingAad
import org.aloeil.app.data.ReadingCipher
import org.aloeil.app.data.ReadingDao
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingRow
import org.aloeil.app.data.SealedPayload
import org.aloeil.app.data.UnreadableLocalStoreException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// assertExists/assertDoesNotExist are SemanticsNodeInteraction member APIs.
// With the pinned Compose BOM 2025.02.00, CI #199 compiled these calls at 7f02d94.
/** Isolated synthetic stores only: unknown failures never authorize resetting saved facts. */
@RunWith(AndroidJUnit4::class)
class StartupFailureClassificationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lazyRoomFailureRetriesWithoutResetOrWritableStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val cipher = SyntheticCipher()
            val original = ReadingRepository(db.readings(), cipher)
            runBlocking {
                original.startSitting("synthetic-retry-sitting")
                original.record("synthetic-retry-reading", "synthetic-retry-sitting", Eye.LEFT, "12.3")
            }
            val attempts = AtomicInteger()
            val dao = object : ReadingDao by db.readings() {
                override suspend fun archiveSnapshot(): ArchiveSnapshotRows {
                    if (attempts.incrementAndGet() == 1) throw SQLiteDiskIOException("Synthetic transient read")
                    return db.readings().archiveSnapshot()
                }
            }
            val resets = AtomicInteger()
            val repository = ReadingRepository(dao, cipher)
            compose.setContent { AloeilApp(repository, resetUnreadableStore = { resets.incrementAndGet(); Unit }) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.startup_retry_title)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(context.getString(R.string.recovery_prepare_reset))).assertDoesNotExist()
            compose.onNode(hasText(context.getString(R.string.start_sitting)) and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText(context.getString(R.string.startup_retry)) and hasClickAction()).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.resume_sitting)) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            check(attempts.get() == 2 && resets.get() == 0)
            runBlocking { check(original.all().single().id == "synthetic-retry-reading") }
        } finally {
            db.close()
        }
    }

    @Test
    fun arbitraryDaoFailuresKeepTheirTypeAndSavedRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val cipher = SyntheticCipher()
            val original = ReadingRepository(db.readings(), cipher)
            original.startSitting("synthetic-types-sitting")
            original.record("synthetic-types-reading", "synthetic-types-sitting", Eye.LEFT, "12.3")
            listOf(SQLiteDiskIOException("synthetic I/O"), IllegalStateException("synthetic migration"),
                IllegalArgumentException("synthetic programming error")).forEach { failure ->
                val dao = object : ReadingDao by db.readings() {
                    override suspend fun archiveSnapshot(): ArchiveSnapshotRows = throw failure
                }
                check(runCatching { ReadingRepository(dao, cipher).verifyReadable() }.exceptionOrNull() === failure)
                check(original.all().size == 1)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun typedSqliteVerificationCorruptionKeepsRowsUntilResetIsConfirmed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val cipher = SyntheticCipher()
            val original = ReadingRepository(db.readings(), cipher)
            runBlocking {
                original.startSitting("synthetic-typed-corruption-sitting")
                original.record("synthetic-typed-corruption-reading", "synthetic-typed-corruption-sitting", Eye.LEFT, "12.3")
            }
            val corruption = SQLiteDatabaseCorruptException("Synthetic typed corruption")
            val dao = object : ReadingDao by db.readings() {
                override suspend fun archiveSnapshot(): ArchiveSnapshotRows = throw corruption
            }
            val repository = ReadingRepository(dao, cipher)
            runBlocking {
                val failure = runCatching { repository.verifyReadable() }.exceptionOrNull()
                check(failure is UnreadableLocalStoreException && failure.cause === corruption)
            }
            val resets = AtomicInteger()
            compose.setContent { AloeilApp(repository, resetUnreadableStore = { resets.incrementAndGet(); Unit }) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.recovery_unreadable_title)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(context.getString(R.string.startup_retry))).assertDoesNotExist()
            runBlocking { check(original.all().size == 1) }
            check(resets.get() == 0)
            compose.onNode(hasText(context.getString(R.string.recovery_prepare_reset)) and hasClickAction())
                .performScrollTo().performClick()
            runBlocking { check(original.all().size == 1) }
            check(resets.get() == 0)
            compose.onNode(hasText(context.getString(R.string.recovery_confirm_reset)) and hasClickAction())
                .performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) { resets.get() == 1 }
            runBlocking { check(original.all().size == 1) }
        } finally {
            db.close()
        }
    }

    @Test
    fun unknownCipherFailureRetriesAndDoesNotDiscardDraft() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val cipher = SyntheticCipher()
            val original = ReadingRepository(db.readings(), cipher)
            original.saveDraft(DraftCheckpoint("synthetic-draft-sitting", "synthetic-draft-reading", "EYE", null, "", "heading"))
            val failure = IOException("Synthetic provider unavailable")
            val opens = AtomicInteger()
            val flaky = object : ReadingCipher by cipher {
                override fun open(payload: SealedPayload, aad: ByteArray): ByteArray {
                    if (opens.incrementAndGet() == 1) throw failure
                    return cipher.open(payload, aad)
                }
            }
            val repository = ReadingRepository(db.readings(), flaky)
            check(runCatching { repository.verifyReadable() }.exceptionOrNull() === failure)
            check(db.readings().draft() != null)
            check(!repository.verifyReadable())
            check(db.readings().draft() != null)
        } finally {
            db.close()
        }
    }

    @Test
    fun savedRowProviderFailureOffersOnlyRetryAndKeepsRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val cipher = SyntheticCipher()
            val original = ReadingRepository(db.readings(), cipher)
            runBlocking {
                original.startSitting("synthetic-provider-sitting")
                original.record("synthetic-provider-reading", "synthetic-provider-sitting", Eye.LEFT, "12.3")
            }
            val opens = AtomicInteger()
            val flaky = object : ReadingCipher by cipher {
                override fun open(payload: SealedPayload, aad: ByteArray): ByteArray {
                    if (opens.incrementAndGet() == 1) throw IOException("Synthetic provider unavailable")
                    return cipher.open(payload, aad)
                }
            }
            val resets = AtomicInteger()
            val repository = ReadingRepository(db.readings(), flaky)
            compose.setContent {
                AloeilApp(repository, resetUnreadableStore = { resets.incrementAndGet(); Unit })
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.startup_retry_title))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(context.getString(R.string.recovery_prepare_reset))).assertDoesNotExist()
            runBlocking { check(original.all().single().id == "synthetic-provider-reading") }
            compose.onNode(hasText(context.getString(R.string.startup_retry)) and hasClickAction()).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.resume_sitting)) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            check(resets.get() == 0)
            runBlocking { check(original.all().single().id == "synthetic-provider-reading") }
        } finally {
            db.close()
        }
    }

    @Test
    fun authenticationAndKeyLossStillEstablishUnreadability() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val cipher = SyntheticCipher()
            val original = ReadingRepository(db.readings(), cipher)
            original.startSitting("synthetic-auth-sitting")
            original.record("synthetic-auth-reading", "synthetic-auth-sitting", Eye.LEFT, "12.3")
            val missing = object : ReadingCipher by cipher {
                override fun open(payload: SealedPayload, aad: ByteArray): ByteArray = throw MissingReadingKeyException()
            }
            listOf(SyntheticCipher(8), missing).forEach { invalid ->
                check(runCatching { ReadingRepository(db.readings(), invalid).verifyReadable() }.exceptionOrNull()
                    is UnreadableLocalStoreException)
                check(original.all().size == 1)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun authenticatedMalformedSavedPayloadStillEstablishesUnreadability() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val cipher = SyntheticCipher()
            val original = ReadingRepository(db.readings(), cipher)
            original.startSitting("synthetic-malformed-sitting")
            val badZone = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeInt(2)
                    out.writeUTF("synthetic-malformed-sitting")
                    out.writeLong(1000L)
                    out.writeUTF("LEFT")
                    out.writeUTF("12.3")
                    out.writeUTF("")
                    out.writeUTF("Synthetic/InvalidZone")
                    out.writeBoolean(false)
                    out.writeBoolean(false)
                }
            }.toByteArray()
            listOf(byteArrayOf(0, 0, 0, 42), byteArrayOf(0), badZone).forEachIndexed { index, payload ->
                val id = "synthetic-malformed-reading-$index"
                val sealed = cipher.seal(payload, ReadingAad.reading(id, 1, 0))
                db.readings().insertReading(ReadingRow(id, sealed.nonce, sealed.ciphertext, 1, 0))
                val failure = runCatching { original.verifyReadable() }.exceptionOrNull()
                check(failure is UnreadableLocalStoreException)
                check(db.readings().allReadings().size == 1)
                db.readings().removeReading(id)
            }
        } finally {
            db.close()
        }
    }
}
