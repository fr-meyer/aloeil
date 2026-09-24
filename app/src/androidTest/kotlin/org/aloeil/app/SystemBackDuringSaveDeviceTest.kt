package org.aloeil.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the Activity's platform Back dispatcher during a deliberately held save. */
@RunWith(AndroidJUnit4::class)
class SystemBackDuringSaveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackCannotEndActivityOrCancelInFlightWrite() {
        val releaseSave = CompletableDeferred<Unit>()
        val busy = mutableStateOf(false)
        val completedWrites = AtomicInteger(0)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val scope = rememberCoroutineScope()
                BlockSystemBackWhileBusy(busy.value)
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
