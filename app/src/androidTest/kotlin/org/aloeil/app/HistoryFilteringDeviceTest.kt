package org.aloeil.app

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.aloeil.app.data.Eye
import org.aloeil.app.data.RangeState
import org.aloeil.app.data.Reading
import org.aloeil.app.data.filterHistory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded synthetic CPU/query tests; no database, file, or actual reading is used. */
@RunWith(AndroidJUnit4::class)
class HistoryFilteringDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val epoch = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()

    private fun reading(index: Int): Reading = Reading(
        id = "synthetic-history-" + index.toString().padStart(5, '0'),
        sittingId = "synthetic-history-sitting",
        recordedAtMillis = epoch + (index % 200) * 60_000L,
        eye = if (index % 2 == 0) Eye.LEFT else Eye.RIGHT,
        value = if (index % 7 == 0) "" else "12.3",
        revision = 1,
        replicaConfirmedRevision = 0,
        rangeState = if (index % 7 == 0) RangeState.BELOW_RANGE else null,
        timeZoneId = when (index % 3) {
            0 -> "Asia/Seoul"
            1 -> "America/Los_Angeles"
            else -> null
        },
    )

    private class ReadGate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val thread = AtomicLong(-1)
        val reads = AtomicInteger(0)

        fun hold() {
            thread.set(Thread.currentThread().id)
            started.countDown()
            try {
                check(release.await(15, TimeUnit.SECONDS)) { "Synthetic History gate timed out" }
            } finally {
                returned.countDown()
            }
        }
    }

    private class HeldReadings(
        private val items: List<Reading>,
        private val gates: List<ReadGate?>,
    ) : AbstractList<Reading>() {
        private val runs = AtomicInteger(0)
        override val size: Int get() = items.size
        override fun get(index: Int): Reading {
            check(Looper.myLooper() != Looper.getMainLooper()) { "History scan ran on Main" }
            if (index == 0) gates.getOrNull(runs.getAndIncrement())?.hold()
            gates.forEach {
                if (it != null && it.thread.get() == Thread.currentThread().id) it.reads.incrementAndGet()
            }
            return items[index]
        }
    }

    private fun await(latch: CountDownLatch) {
        check(latch.await(15, TimeUnit.SECONDS)) { "Synthetic History worker did not reach its gate" }
    }

    @Test
    fun backgroundFilterPreservesEyeDatesZonesInvalidInputAndTieOrdering() = runBlocking {
        val items = List(513, ::reading)
        val queries = listOf(
            Triple<Eye?, String, String>(null, "", ""),
            Triple(Eye.LEFT, "2026-01-01", "2026-01-01"),
            Triple(Eye.RIGHT, "2025-12-31", "2025-12-31"),
            Triple(null, " 2025-12-31 ", "2026-01-01"),
            Triple(Eye.RIGHT, "bad", ""),
            Triple(null, "2026-01-02", "2026-01-01"),
            Triple(null, "", "bad"),
        )
        withTimeout(15_000) {
            for ((eye, from, to) in queries) {
                val expected = filterHistory(items, eye, from, to)
                val actual = historyForDisplay(items, eye, from, to)
                check(actual.filtered == expected)
                check(actual.numericReadings == expected.readings.filter { it.rangeState == null })
            }
            check(historyForDisplay(emptyList(), null, "bad", "").filtered ==
                filterHistory(emptyList(), null, "bad", ""))
        }
    }

    @Test
    fun graphLimitKeepsRangeFactsAndPreservesExistingNumericBoundary() = runBlocking {
        val items = List(2001) { reading(it).copy(value = "12.3", rangeState = null) }
        withTimeout(15_000) {
            val atLimit = historyForDisplay(items.take(2000), null, "", "")
            check(atLimit.filtered.readings.size == 2000 && atLimit.numericReadings?.size == 2000)
            val aboveLimit = historyForDisplay(items, null, "", "")
            check(aboveLimit.filtered.readings.size == 2001 && aboveLimit.numericReadings == null)
            val rangeOnly = items.map { it.copy(value = "", rangeState = RangeState.ABOVE_RANGE) }
            val ranges = historyForDisplay(rangeOnly, null, "", "")
            check(ranges.filtered.readings.size == 2001 && ranges.numericReadings?.isEmpty() == true)
        }
    }

    @Test
    fun cancelledLargeFilterStopsBeforeScanningTheRemainingHistory() = runBlocking {
        val items = List(25_000, ::reading)
        val gate = ReadGate()
        val held = HeldReadings(items, listOf(gate))
        withTimeout(30_000) {
            val outdated = async(start = CoroutineStart.UNDISPATCHED) {
                historyForDisplay(held, null, "", "")
            }
            try {
                withContext(Dispatchers.IO) { await(gate.started) }
                outdated.cancel()
                val latest = historyForDisplay(held, Eye.RIGHT, "2025-12-31", "2025-12-31")
                check(latest.filtered == filterHistory(items, Eye.RIGHT, "2025-12-31", "2025-12-31"))
                check(gate.release.count == 1L) // Latest query completes while the old worker is held.
                gate.release.countDown()
                outdated.join()
                check(outdated.isCancelled)
                check(gate.reads.get() in 1 until items.size / 2)
            } finally {
                gate.release.countDown()
                withContext(NonCancellable) { outdated.cancelAndJoin() }
            }
        }
    }

    @Test
    fun changingQueryHidesOldCountAndRowsAndOnlyPublishesTheLatestSnapshot() {
        val items = List(25_000, ::reading)
        val outdated = ReadGate()
        // Let the initial result render; hold only one superseded worker, leaving
        // Default's other worker available even on a two-core test emulator.
        val held = HeldReadings(items, listOf(null, outdated))
        var eye by mutableStateOf<Eye?>(null)
        var from by mutableStateOf("")
        var to by mutableStateOf("")
        try {
            compose.setContent {
                val result = rememberHistoryDisplay(held, eye, from, to)
                if (result == null) {
                    Text("synthetic-pending")
                } else {
                    Text("synthetic-count:" + result.filtered.readings.size)
                    Text("synthetic-first:" + result.filtered.readings.firstOrNull()?.id)
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText("synthetic-count:" + items.size))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { eye = Eye.RIGHT; from = "2025-12-31"; to = "2025-12-31" }
            await(outdated.started)
            compose.onNode(hasText("synthetic-pending")).assertExists()
            compose.onNode(hasText("synthetic-count:", substring = true)).assertDoesNotExist()
            compose.onNode(hasText("synthetic-first:", substring = true)).assertDoesNotExist()
            compose.runOnIdle { eye = Eye.LEFT; from = "2026-01-01"; to = "2026-01-01" }
            val expected = filterHistory(items, Eye.LEFT, "2026-01-01", "2026-01-01")
            val count = hasText("synthetic-count:" + expected.readings.size)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(count).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("synthetic-first:" + expected.readings.first().id)).assertExists()
            check(outdated.release.count == 1L)
            outdated.release.countDown()
            await(outdated.returned)
            compose.waitForIdle()
            compose.onNode(count).assertExists()
            compose.onNode(hasText("synthetic-first:" + expected.readings.first().id)).assertExists()
        } finally {
            outdated.release.countDown()
        }
    }
}
