package org.aloeil.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Synthetic locale and enlarged-text capture checks on a narrow viewport. */
@RunWith(AndroidJUnit4::class)
class LocaleScaleDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishCaptureAtLargeText() = captureAtLargeText("en")
    @Test fun frenchCaptureAtLargeText() = captureAtLargeText("fr")
    @Test fun koreanCaptureAtLargeText() = captureAtLargeText("ko")

    private fun captureAtLargeText(languageTag: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        val localized = context.createConfigurationContext(configuration)
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher())
            compose.setContent {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.Locales(LocaleList(languageTag)) then
                        DeviceConfigurationOverride.FontScale(2.0f) then
                        DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 600.dp)),
                ) {
                    AloeilApp(repo)
                }
            }
            fun heading(id: Int) {
                val target = hasText(localized.getString(id)) and isHeading()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performScrollTo().assertIsDisplayed()
            }
            fun tap(id: Int) {
                val target = hasText(localized.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performScrollTo().assertIsDisplayed().performClick()
            }

            heading(R.string.start_sitting)
            tap(R.string.start_sitting)
            heading(R.string.choose_eye)
            tap(R.string.left_eye)
            compose.onNode(hasText(localized.getString(R.string.left_eye)) and hasClickAction())
                .assertIsSelected()
            tap(R.string.continue_action)
            heading(R.string.enter_reading)
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("12.3")
            tap(R.string.continue_action)
            heading(R.string.add_note)
            tap(R.string.continue_action)
            heading(R.string.review_before_save)
            tap(R.string.save_reading)
            heading(R.string.saved_on_phone)
            runBlocking {
                check(repo.all().single().value == "12.3")
            }
        } finally {
            db.close()
        }
    }
}
