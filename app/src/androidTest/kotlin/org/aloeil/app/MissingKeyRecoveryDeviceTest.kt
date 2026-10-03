package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.AndroidKeystoreReadingCipher
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.Eye
import org.aloeil.app.data.MissingReadingKeyException
import org.aloeil.app.data.ReadingCipher
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.SealedPayload
import org.aloeil.app.data.UnreadableLocalStoreException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic records are used; the reset is always confirmed in the UI. */
@RunWith(AndroidJUnit4::class)
class MissingKeyRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unreadableRowsRequireTwoExplicitActionsBeforeReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val original = ReadingRepository(db.readings(), SyntheticCipher())
            runBlocking {
                original.startSitting("synthetic-sitting")
                original.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
            }
            val unreadable = ReadingRepository(db.readings(), SyntheticCipher(8))
            runBlocking {
                val failure = runCatching { unreadable.verifyReadable() }.exceptionOrNull()
                check(failure is UnreadableLocalStoreException)
            }
            val confirmedResets = AtomicInteger(0)
            compose.setContent {
                AloeilApp(unreadable, resetUnreadableStore = {
                    val attempt = confirmedResets.incrementAndGet()
                    if (attempt == 1) throw IllegalStateException("synthetic reset failure")
                    throw RecoveryResetFailure(
                        databaseDeleted = true,
                        cause = IllegalStateException("synthetic key cleanup failure"),
                    )
                })
            }
            fun tap(id: Int) {
                val target = hasText(context.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performScrollTo().performClick()
            }
            tap(R.string.recovery_prepare_reset)
            check(confirmedResets.get() == 0)
            runBlocking { check(db.readings().allReadings().size == 1) }
            tap(R.string.back)
            check(confirmedResets.get() == 0)
            tap(R.string.recovery_prepare_reset)
            tap(R.string.recovery_confirm_reset)
            compose.waitUntil(timeoutMillis = 5_000) { confirmedResets.get() == 1 }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText(context.getString(R.string.recovery_reset_error)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking { check(db.readings().allReadings().size == 1) }
            tap(R.string.recovery_prepare_reset)
            tap(R.string.recovery_confirm_reset)
            compose.waitUntil(timeoutMillis = 5_000) {
                confirmedResets.get() == 2 &&
                    compose.onAllNodes(hasText(context.getString(R.string.recovery_key_cleanup_error)))
                        .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun databaseDeletionFailureKeepsKeyAndKeyFailureReportsPartialReset() {
        val calls = mutableListOf<String>()
        val databaseFailure = IllegalStateException("synthetic database failure")
        val beforeDeletion = runCatching {
            resetUnreadableLocalStore(
                deleteKey = { calls += "key" },
                deleteDatabase = { calls += "database"; throw databaseFailure },
            )
        }.exceptionOrNull() as? RecoveryResetFailure
        check(beforeDeletion?.databaseDeleted == false)
        check(beforeDeletion?.cause === databaseFailure)
        check(calls == listOf("database"))

        calls.clear()
        val keyFailure = IllegalStateException("synthetic key failure")
        val afterDeletion = runCatching {
            resetUnreadableLocalStore(
                deleteKey = { calls += "key"; throw keyFailure },
                deleteDatabase = { calls += "database" },
            )
        }.exceptionOrNull() as? RecoveryResetFailure
        check(afterDeletion?.databaseDeleted == true)
        check(afterDeletion?.cause === keyFailure)
        check(calls == listOf("database", "key"))
    }

    @Test
    fun cancellationDuringDraftVerificationIsNeverTreatedAsSuccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            ReadingRepository(db.readings(), SyntheticCipher()).saveDraft(
                DraftCheckpoint(
                    "synthetic-sitting", "synthetic-reading", "EYE",
                    null, "", "heading",
                ),
            )
            val cancelled = CancellationException("synthetic verification cancelled")
            val cancellingCipher = object : ReadingCipher {
                override fun seal(plaintext: ByteArray, aad: ByteArray): SealedPayload =
                    error("Verification must not write")
                override fun open(payload: SealedPayload, aad: ByteArray): ByteArray = throw cancelled
            }
            val repo = ReadingRepository(db.readings(), cancellingCipher)
            check(runCatching { repo.verifyReadable() }.exceptionOrNull() === cancelled)
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentFirstUseNeverReplacesTheReadingKey() {
        val alias = "aloeil-synthetic-first-use-" + java.util.UUID.randomUUID()
        val cleanup = AndroidKeystoreReadingCipher(alias)
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val sealed = (0 until 8).map { index ->
                workers.submit<Pair<ByteArray, SealedPayload>> {
                    check(start.await(10, TimeUnit.SECONDS))
                    val original = "synthetic-$index".toByteArray()
                    original to AndroidKeystoreReadingCipher(alias).seal(original, "first-use".toByteArray())
                }
            }
            start.countDown()
            sealed.forEach { future ->
                val (original, payload) = future.get(30, TimeUnit.SECONDS)
                check(cleanup.open(payload, "first-use".toByteArray()).contentEquals(original))
            }
        } finally {
            workers.shutdownNow()
            cleanup.deleteKeyForRecovery()
        }
    }

    @Test
    fun missingKeystoreKeyCannotBeSilentlyRegeneratedOnOpen() {
        val alias = "aloeil-synthetic-key-loss-" + java.util.UUID.randomUUID()
        val cipher = AndroidKeystoreReadingCipher(alias)
        try {
            val sealed = cipher.seal("synthetic".toByteArray(), "key-loss".toByteArray())
            cipher.deleteKeyForRecovery()
            check(runCatching { cipher.open(sealed, "key-loss".toByteArray()) }.exceptionOrNull()
                is MissingReadingKeyException)
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!store.containsAlias(alias))
        } finally {
            cipher.deleteKeyForRecovery()
        }
    }
}
