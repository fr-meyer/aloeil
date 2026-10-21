package org.aloeil.app

import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Android 15+ exposed light icons and content under the status bar. */
@RunWith(AndroidJUnit4::class)
class SystemInsetsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun headingClearsSystemBarsAndLightSurfaceUsesDarkIcons() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(isHeading()).fetchSemanticsNodes().isNotEmpty()
        }
        val heading = compose.onAllNodes(isHeading()).fetchSemanticsNodes().first()
        compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            val insets = WindowInsetsCompat.toWindowInsetsCompat(
                view.rootWindowInsets, view,
            ).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            check(heading.boundsInWindow.top >= insets.top)
            val controller = WindowCompat.getInsetsController(window, view)
            check(controller.isAppearanceLightStatusBars)
            check(controller.isAppearanceLightNavigationBars)
        }
    }
}
