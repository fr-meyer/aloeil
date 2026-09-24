package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A closed history correction must not masquerade as an open capture sitting. */
@RunWith(AndroidJUnit4::class)
class HistoryDraftRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun closedHistoryDraftCanReturnAndStartNewSitting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-history-draft-" + UUID.randomUUID() + ".db"
        val cipher = SyntheticCipher()
        val first = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            runBlocking {
                val repo = ReadingRepository(first.readings(), cipher)
                repo.startSitting("closed-sitting")
                repo.record("old-reading", "closed-sitting", Eye.LEFT, "12.3")
                check(repo.finishSitting("closed-sitting"))
                repo.saveDraft(DraftCheckpoint(
                    "closed-sitting", "old-reading", "CORRECT_CHOICE", Eye.LEFT,
                    "12.3", "heading", baseRevision = 1, fromHistory = true,
                ))
            }
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val repo = ReadingRepository(reopened.readings(), cipher)
            compose.setContent { AloeilApp(repo) }
            fun tap(id: Int) {
                val label = context.getString(id)
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
            tap(R.string.back)
            tap(R.string.history_back)
            tap(R.string.back)
            tap(R.string.start_sitting)
            tap(R.string.left_eye)
            runBlocking {
                val open = repo.openSitting() ?: error("New sitting was not created")
                check(open.id != "closed-sitting")
                check(repo.all().size == 1)
            }
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("14.2")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            tap(R.string.save_reading)
            try {
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(hasText(context.getString(R.string.saved_on_phone)))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: Exception) {
                throw AssertionError(
                    "Expected result missing: saved_on_phone\n" + compose.onRoot().printToString().take(4000),
                    error,
                )
            }
            runBlocking { check(repo.all().size == 2) }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
