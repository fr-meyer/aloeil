package org.aloeil.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies navigation is blocked while an asynchronous save is in flight. */
@RunWith(AndroidJUnit4::class)
class BusyNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rapidBackDuringSaveCannotNavigate() {
        val releaseSave = CompletableDeferred<Unit>()
        val busy = mutableStateOf(false)
        val navigations = AtomicInteger(0)
        val backLabel = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.back)
        compose.setContent {
            val scope = rememberCoroutineScope()
            Column {
                Button(onClick = {
                    busy.value = true
                    scope.launch {
                        releaseSave.await()
                        busy.value = false
                    }
                }) { Text("Synthetic Save") }
                Secondary(R.string.back, busy.value) { navigations.incrementAndGet() }
            }
        }
        compose.onNodeWithText("Synthetic Save").performClick()
        compose.onNodeWithText(backLabel).assertIsNotEnabled()
        runCatching { compose.onNodeWithText(backLabel).performClick() }
        check(navigations.get() == 0)
        releaseSave.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { !busy.value }
        compose.onNodeWithText(backLabel).assertIsEnabled().performClick()
        check(navigations.get() == 1)
    }
}
