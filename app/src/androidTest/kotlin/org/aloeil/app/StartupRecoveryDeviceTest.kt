package org.aloeil.app

import androidx.activity.compose.setContent
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.MissingReadingKeyException
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.UnreadableLocalStoreException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// assertExists/assertDoesNotExist are SemanticsNodeInteraction member APIs.
// With the pinned Compose BOM 2025.02.00, CI #199 compiled these calls at 7f02d94.
/** Unknown initialization failures only retry; proven unreadability keeps guarded recovery. */
@RunWith(AndroidJUnit4::class)
class StartupRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun classifiedUnreadabilityNeedsTwoExplicitResetActions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resets = AtomicInteger()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                AloeilStartup(
                    Result.failure(UnreadableLocalStoreException(MissingReadingKeyException())),
                    resetUnreadableStore = { resets.incrementAndGet(); Unit },
                )
            }
        }
        compose.onNode(hasText(context.getString(R.string.recovery_unreadable_title))).assertExists()
        check(resets.get() == 0)
        compose.onNode(hasText(context.getString(R.string.recovery_prepare_reset)) and hasClickAction())
            .performClick()
        compose.onNode(hasText(context.getString(R.string.recovery_confirm_title))).assertExists()
        check(resets.get() == 0)
        compose.onNode(hasText(context.getString(R.string.recovery_confirm_reset)) and hasClickAction())
            .performClick()
        compose.waitUntil(timeoutMillis = 10_000) { resets.get() == 1 }
    }

    @Test
    fun unknownConstructionFailureRetriesWithoutOfferingReset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repository = ReadingRepository(db.readings(), SyntheticCipher())
            runBlocking {
                repository.startSitting("synthetic-construction-sitting")
                repository.record("synthetic-construction-reading", "synthetic-construction-sitting", Eye.LEFT, "12.3")
            }
            val result = mutableStateOf<Result<ReadingRepository>>(
                Result.failure(IllegalStateException("Synthetic construction failure")),
            )
            val resets = AtomicInteger()
            val retries = AtomicInteger()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    AloeilStartup(result.value,
                        resetUnreadableStore = { resets.incrementAndGet(); Unit },
                        retryStartup = { retries.incrementAndGet(); result.value = Result.success(repository) },
                    )
                }
            }
            compose.onNode(hasText(context.getString(R.string.startup_retry_title))).assertExists()
            compose.onNode(hasText(context.getString(R.string.recovery_prepare_reset))).assertDoesNotExist()
            compose.onNode(hasText(context.getString(R.string.recovery_confirm_reset))).assertDoesNotExist()
            compose.onNode(hasText(context.getString(R.string.startup_retry)) and hasClickAction()).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.resume_sitting)) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            check(retries.get() == 1 && resets.get() == 0)
            runBlocking { check(repository.all().single().id == "synthetic-construction-reading") }
        } finally {
            db.close()
        }
    }

    @Test
    fun typedSqliteConstructionCorruptionKeepsRowsUntilResetIsConfirmed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repository = ReadingRepository(db.readings(), SyntheticCipher())
            runBlocking {
                repository.startSitting("synthetic-corrupt-construction-sitting")
                repository.record("synthetic-corrupt-construction-reading", "synthetic-corrupt-construction-sitting", Eye.LEFT, "12.3")
            }
            val resets = AtomicInteger()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    AloeilStartup(Result.failure(SQLiteDatabaseCorruptException("Synthetic typed corruption")),
                        resetUnreadableStore = { resets.incrementAndGet(); Unit },
                    )
                }
            }
            compose.onNode(hasText(context.getString(R.string.recovery_unreadable_title))).assertExists()
            compose.onNode(hasText(context.getString(R.string.startup_retry))).assertDoesNotExist()
            check(resets.get() == 0)
            runBlocking { check(repository.all().size == 1) }
            compose.onNode(hasText(context.getString(R.string.recovery_prepare_reset)) and hasClickAction())
                .performScrollTo().performClick()
            check(resets.get() == 0)
            runBlocking { check(repository.all().size == 1) }
            compose.onNode(hasText(context.getString(R.string.recovery_confirm_reset)) and hasClickAction())
                .performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) { resets.get() == 1 }
            // The callback is only a counter: this test never corrupts or deletes a database.
            runBlocking { check(repository.all().size == 1) }
        } finally {
            db.close()
        }
    }
}
