package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic end-to-end access after the saved draft has been cleared. */
@RunWith(AndroidJUnit4::class)
class HistoryRestartDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun finishedReadingIsSelectableAfterDatabaseRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-history-" + UUID.randomUUID() + ".db"
        val cipher = SyntheticCipher()
        val first = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            runBlocking {
                val repo = ReadingRepository(first.readings(), cipher, { "Asia/Seoul" }) {
                    1_700_000_000_000L
                }
                repo.startSitting("synthetic-sitting")
                repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
                repo.clearDraft()
                check(repo.finishSitting("synthetic-sitting"))
            }
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val repo = ReadingRepository(reopened.readings(), cipher)
            runBlocking {
                check(repo.recoverDraft() == null)
                check(repo.openSitting() == null)
                check(repo.all().single().id == "synthetic-reading")
            }
            compose.setContent { AloeilApp(repo) }
            fun tap(label: String) {
                val target = hasText(label) and hasClickAction()
                try {
                    compose.waitUntil(timeoutMillis = 10_000) {
                        compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (error: Exception) {
                    throw AssertionError(
                        "Could not tap: " + label + "\n" + compose.onRoot().printToString().take(4000),
                        error,
                    )
                }
                compose.onNode(target).performClick()
            }
            tap(context.getString(R.string.history_title))
            val label = context.getString(R.string.left_eye) + ": " +
                context.getString(R.string.numeric_reading, "12.3")
            tap(label)
            try {
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(hasText(context.getString(R.string.history_detail_title)))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: Exception) {
                throw AssertionError(
                    "Expected result missing: history_detail_title\n" + compose.onRoot().printToString().take(4000),
                    error,
                )
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
