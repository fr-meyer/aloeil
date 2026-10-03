package org.aloeil.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the Activity's platform Back dispatcher during a deliberately held save. */
@RunWith(AndroidJUnit4::class)
class SystemBackDuringSaveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackDuringDraftKeepsLatestInputForRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher())
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { AloeilApp(repo) }
            }
            fun tap(id: Int) {
                val target = hasText(context.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performScrollTo().performClick()
            }
            tap(R.string.start_sitting)
            tap(R.string.left_eye)
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("12.3")
            compose.activityRule.scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                check(!activity.isFinishing)
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                runBlocking { repo.recoverDraft()?.first?.input == "12.3" }
            }
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { AloeilApp(repo) }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasSetTextAction()).assertTextContains("12.3")
        } finally {
            db.close()
        }
    }

    @Test
    fun systemBackCannotEndActivityOrCancelInFlightWrite() {
        val releaseSave = CompletableDeferred<Unit>()
        val busy = mutableStateOf(false)
        val completedWrites = AtomicInteger(0)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val scope = rememberCoroutineScope()
                BlockSystemBackWhenUnsafe(busy.value)
                Column {
                    Button(onClick = {
                        busy.value = true
                        scope.launch {
                            releaseSave.await()
                            completedWrites.incrementAndGet()
                            busy.value = false
                        }
                    }) { Text("Synthetic delayed write") }
                }
            }
        }
        compose.onNodeWithText("Synthetic delayed write").performClick()
        compose.waitForIdle()
        check(busy.value)
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
            check(!activity.isFinishing)
        }
        releaseSave.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) {
            !busy.value && completedWrites.get() == 1
        }
        compose.activityRule.scenario.onActivity { activity ->
            check(!activity.isFinishing)
        }
    }
}
