package org.aloeil.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aloeil.app.data.ArchiveMaterializationLimitException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Processing limits are distinguishable from wrong passphrases, with no archive contents in state. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ArchiveCapacityFeedbackDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun limitMessageDoesNotMisclassifyOtherFailures() {
        val limit = ArchiveMaterializationLimitException()
        for (fallback in listOf(R.string.archive_read_error, R.string.archive_export_error)) {
            check(archiveErrorMessage(limit, fallback) == R.string.archive_capacity_error)
            check(archiveErrorMessage(IllegalArgumentException("Synthetic failure"), fallback) == fallback)
        }
    }

    @Test
    fun importOwnerKeepsOnlyLimitStatusAndScrubsInputsBeforeReporting() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = ArchiveImportJob(owner)
            suspend fun finish(fail: Boolean, capacity: Boolean): ArchiveImportState.Finished {
                val bytes = ByteArray(64) { 7 }
                val secret = "synthetic-only".toCharArray()
                val id = job.start(bytes, secret) { _, _ ->
                    if (capacity) throw ArchiveMaterializationLimitException()
                    if (fail) throw IllegalArgumentException("Synthetic failure")
                    0
                }
                val result = withTimeout(10_000) {
                    job.state.first { it is ArchiveImportState.Finished && it.id == id }
                } as ArchiveImportState.Finished
                check(bytes.all { it == 0.toByte() } && secret.all { it == '\u0000' })
                return result
            }
            val limited = finish(fail = true, capacity = true)
            check(!limited.success && limited.added == null && limited.capacityExceeded)
            val otherFailure = finish(fail = true, capacity = false)
            check(!otherFailure.success && !otherFailure.capacityExceeded)
            val success = finish(fail = false, capacity = false)
            check(success.success && success.added == 0 && !success.capacityExceeded)
        } finally {
            owner.cancel()
        }
    }

    @Test fun englishLimitFeedbackAtLargeText() = limitFeedback("en")
    @Test fun frenchLimitFeedbackAtLargeText() = limitFeedback("fr")
    @Test fun koreanLimitFeedbackAtLargeText() = limitFeedback("ko")

    private fun limitFeedback(language: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val expected = context.createConfigurationContext(configuration)
            .getString(R.string.archive_capacity_error)
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.Locales(LocaleList(language)) then
                    DeviceConfigurationOverride.FontScale(2f) then
                    DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 600.dp)),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ArchiveErrorFeedback(R.string.archive_capacity_error)
                }
            }
        }
        val node = compose.onNode(hasText(expected))
        node.assertIsDisplayed()
        check(node.fetchSemanticsNode().config[SemanticsProperties.LiveRegion] == LiveRegionMode.Assertive)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
        val layout = layouts.single()
        check(layout.lineCount > 1 && !layout.hasVisualOverflow)
    }
}
