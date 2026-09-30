package org.aloeil.app

import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh synthetic test installation only. The chooser is intercepted; no CSV is sent. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReviewTransferLocaleDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishReviewAndTransfersAtLargeText() = reviewAndTransfers("en")
    @Test fun frenchReviewAndTransfersAtLargeText() = reviewAndTransfers("fr")
    @Test fun koreanReviewAndTransfersAtLargeText() = reviewAndTransfers("ko")

    private fun reviewAndTransfers(languageTag: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localized = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            },
        )
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val chooserOpens = AtomicInteger(0)
        val createdShares = mutableListOf<java.io.File>()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher(), { "UTC" }) {
                1_700_000_000_000L
            }
            runBlocking {
                repo.startSitting("synthetic-review-sitting")
                repo.record("synthetic-review-left", "synthetic-review-sitting", Eye.LEFT, "12.3")
                repo.record("synthetic-review-right", "synthetic-review-sitting", Eye.RIGHT, "13.4")
                check(repo.finishSitting("synthetic-review-sitting"))
            }
            // These UI/cache tests are only for a fresh synthetic installation, never a user's app.
            CsvShareCache.clearAll(context)
            compose.setContent {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.Locales(LocaleList(languageTag)) then
                        DeviceConfigurationOverride.FontScale(2.0f) then
                        DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 600.dp)),
                ) {
                    val base = LocalContext.current
                    val chooserContext = remember(base) {
                        object : ContextWrapper(base) {
                            override fun startActivity(intent: Intent) {
                                check(intent.action == Intent.ACTION_CHOOSER)
                                chooserOpens.incrementAndGet()
                                // Model returning without a recipient: Android supplies no callback.
                            }
                        }
                    }
                    CompositionLocalProvider(LocalContext provides chooserContext) {
                        AloeilApp(repo)
                    }
                }
            }

            fun waitFor(matcher: SemanticsMatcher) {
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
                }
            }
            fun heading(id: Int) {
                val matcher = hasText(localized.getString(id)) and isHeading()
                waitFor(matcher)
                compose.onNode(matcher).performScrollTo().assertIsDisplayed()
            }
            fun action(id: Int) = compose.onNode(
                hasText(localized.getString(id)) and hasClickAction(),
            )
            fun checkAction(id: Int) {
                waitFor(hasText(localized.getString(id)) and hasClickAction())
                action(id).performScrollTo().assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            }
            fun tap(id: Int) {
                checkAction(id)
                action(id).performClick()
            }
            fun notice(id: Int, mode: LiveRegionMode) {
                val matcher = hasText(localized.getString(id))
                waitFor(matcher)
                compose.onNode(matcher).performScrollTo().assertIsDisplayed()
                check(compose.onNode(matcher).fetchSemanticsNode()
                    .config[SemanticsProperties.LiveRegion] == mode)
            }

            tap(R.string.history_title)
            heading(R.string.history_title)
            waitFor(hasText(localized.getString(R.string.history_count, 2)))
            tap(R.string.left_eye)
            action(R.string.left_eye).assertIsSelected()
            waitFor(hasText(localized.getString(R.string.history_count, 1)))
            val reading = hasText(localized.getString(R.string.left_eye) + ": " +
                localized.getString(R.string.numeric_reading, "12.3")) and hasClickAction()
            waitFor(reading)
            compose.onNode(reading).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).performClick()
            heading(R.string.history_detail_title)
            checkAction(R.string.correct_reading)
            checkAction(R.string.delete_reading)
            tap(R.string.back)
            heading(R.string.history_title)
            val fromDate = hasText(localized.getString(R.string.history_from_date)) and
                hasSetTextAction()
            compose.onNode(fromDate).performScrollTo().performTextInput("not-a-date")
            notice(R.string.history_invalid_date, LiveRegionMode.Assertive)
            compose.onNode(fromDate).performScrollTo().performTextClearance()
            compose.onNode(hasText(localized.getString(R.string.history_invalid_date)))
                .assertDoesNotExist()
            tap(R.string.back)

            tap(R.string.archive_title)
            heading(R.string.archive_title)
            tap(R.string.archive_create)
            heading(R.string.archive_create)
            val password = hasSetTextAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
            compose.onNode(password).performScrollTo().assertIsDisplayed()
                .performTextInput("short")
            tap(R.string.archive_choose_destination)
            notice(R.string.archive_passphrase_short, LiveRegionMode.Assertive)
            tap(R.string.back)
            tap(R.string.archive_restore)
            heading(R.string.archive_restore)
            compose.onNode(password).performScrollTo().assertIsDisplayed()
            checkAction(R.string.archive_choose_file)
            action(R.string.archive_choose_file).assertIsNotEnabled()
            tap(R.string.back)
            tap(R.string.back)

            tap(R.string.csv_title)
            heading(R.string.csv_title)
            val disclosure = hasText(localized.getString(R.string.csv_cache_disclosure))
            waitFor(disclosure)
            compose.onNode(disclosure).performScrollTo().assertIsDisplayed()
            check(compose.onNode(disclosure).getUnclippedBoundsInRoot().bottom <=
                action(R.string.csv_share).getUnclippedBoundsInRoot().top)
            check(chooserOpens.get() == 0)
            check(CsvShareCache.directory(context).listFiles()?.none { it.isFile } != false)
            compose.onNode(hasText(localized.getString(R.string.csv_clear_cache)))
                .assertDoesNotExist()
            checkAction(R.string.csv_save)
            tap(R.string.csv_share)
            notice(R.string.csv_chooser_opened, LiveRegionMode.Polite)
            check(chooserOpens.get() == 1)
            val file = CsvShareCache.directory(context).listFiles()!!.single { it.isFile }
            createdShares += file
            // Simulated chooser cancellation leaves the file and its scheduled cleanup intact.
            check(file.exists())
            val scheduler = context.getSystemService(JobScheduler::class.java)
            check(scheduler.getPendingJob(CsvShareCache.jobId(file))?.isPersisted == true)
            tap(R.string.csv_clear_cache)
            notice(R.string.csv_cache_cleared, LiveRegionMode.Polite)
            check(!file.exists())
            compose.onNode(hasText(localized.getString(R.string.csv_clear_cache)))
                .assertDoesNotExist()
            compose.onNode(disclosure).performScrollTo().assertIsDisplayed()
        } finally {
            val remainingShares = CsvShareCache.directory(context).listFiles().orEmpty().toList()
            CsvShareCache.clearAll(context)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            (createdShares + remainingShares).distinctBy { it.path }.forEach {
                scheduler.cancel(CsvShareCache.jobId(it))
            }
            db.close()
        }
    }
}
