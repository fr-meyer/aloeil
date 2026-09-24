package org.aloeil.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aloeil.app.data.Eye
import org.aloeil.app.data.RangeState
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.Sitting
import org.aloeil.app.data.filterHistory
import org.aloeil.app.data.readingZone
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun HistoryScreen(
    repository: ReadingRepository,
    onSelect: (Reading, Sitting?) -> Unit,
    onBack: () -> Unit,
) {
    var readings by remember { mutableStateOf<List<Reading>?>(null) }
    var sittings by remember { mutableStateOf<Map<String, Sitting>>(emptyMap()) }
    var loadError by remember { mutableStateOf(false) }
    var eye by remember { mutableStateOf<Eye?>(null) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var visibleCount by remember { mutableStateOf(50) }

    LaunchedEffect(eye, fromText, toText) { visibleCount = 50 }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                repository.all() to repository.allSittings().associateBy { it.id }
            }
        }.onSuccess { (items, groups) ->
            readings = items
            sittings = groups
        }.onFailure { loadError = true }
    }

    Text(stringResource(R.string.history_title), modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium)
    if (loadError) {
        Text(stringResource(R.string.history_load_error), color = MaterialTheme.colorScheme.error)
        Secondary(R.string.back, false, onBack)
        return
    }
    val all = readings
    if (all == null) {
        Text(stringResource(R.string.loading))
        return
    }
    if (all.isEmpty()) {
        Text(stringResource(R.string.history_empty))
        Secondary(R.string.back, false, onBack)
        return
    }

    Text(stringResource(R.string.history_filter_eye))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(null, Eye.LEFT, Eye.RIGHT).forEach { option ->
            val label = when (option) {
                Eye.LEFT -> R.string.left_eye
                Eye.RIGHT -> R.string.right_eye
                null -> R.string.history_all_eyes
            }
            FilterChip(
                selected = eye == option,
                onClick = { eye = option },
                label = { Text(stringResource(label)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
        }
    }
    OutlinedTextField(
        value = fromText,
        onValueChange = { fromText = it },
        label = { Text(stringResource(R.string.history_from_date)) },
        supportingText = { Text(stringResource(R.string.history_date_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = toText,
        onValueChange = { toText = it },
        label = { Text(stringResource(R.string.history_to_date)) },
        supportingText = { Text(stringResource(R.string.history_date_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val filtered = filterHistory(all, eye, fromText, toText)
    if (filtered.invalidDate) {
        Text(stringResource(R.string.history_invalid_date), color = MaterialTheme.colorScheme.error)
    } else {
        Text(stringResource(R.string.history_count, filtered.readings.size))
        if (filtered.readings.isEmpty()) {
            Text(stringResource(R.string.history_no_match))
        } else {
            if (filtered.readings.count { it.rangeState == null } <= 2000) {
                ReadingGraph(filtered.readings)
            } else {
                Text(stringResource(R.string.history_graph_limit))
            }
            Text(stringResource(R.string.history_list_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge)
            filtered.readings.take(visibleCount).forEach { reading ->
                OutlinedButton(
                    onClick = { onSelect(reading, sittings[reading.sittingId]) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(eyeLabel(reading.eye) + ": " + readingLabel(reading.value, reading.rangeState))
                        Text(formatReadingTime(reading), style = MaterialTheme.typography.bodyMedium)
                        if (reading.note != null) {
                            Text(stringResource(R.string.note_summary, reading.note))
                        }
                    }
                }
            }
            if (filtered.readings.size > visibleCount) {
                Secondary(R.string.history_show_more, false) { visibleCount += 50 }
            }
        }
    }
    Secondary(R.string.back, false, onBack)
}

@Composable
private fun ReadingGraph(readings: List<Reading>) {
    val numeric = readings.filter { it.rangeState == null }
    val rangeCount = readings.size - numeric.size
    Text(stringResource(R.string.history_graph_title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge)
    if (rangeCount > 0) Text(stringResource(R.string.history_range_list_only, rangeCount))
    if (numeric.isEmpty()) {
        Text(stringResource(R.string.history_graph_empty))
        return
    }
    val leftCount = numeric.count { it.eye == Eye.LEFT }
    val rightCount = numeric.size - leftCount
    Text(stringResource(R.string.history_graph_legend, leftCount, rightCount))
    val minValue = numeric.minOf { BigDecimal(it.value) }
    val maxValue = numeric.maxOf { BigDecimal(it.value) }
    Text(stringResource(
        R.string.history_graph_value_span, minValue.toPlainString(), maxValue.toPlainString(),
    ))
    val minTime = numeric.minOf { it.recordedAtMillis }
    val maxTime = numeric.maxOf { it.recordedAtMillis }
    val leftColor = MaterialTheme.colorScheme.onSurface
    val rightColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.history_graph_description)
    Canvas(
        modifier = Modifier.fillMaxWidth().height(220.dp).semantics {
            contentDescription = description
        },
    ) {
        val inset = 24.dp.toPx()
        val graphWidth = (size.width - 2 * inset).coerceAtLeast(1f)
        val graphHeight = (size.height - 2 * inset).coerceAtLeast(1f)
        val span = maxValue.subtract(minValue)
        fun point(item: Reading): Offset {
            val xRatio = if (minTime == maxTime) 0.5f else
                ((item.recordedAtMillis.toDouble() - minTime.toDouble()) /
                    (maxTime.toDouble() - minTime.toDouble())).toFloat()
            val yRatio = if (span.signum() == 0) 0.5f else
                BigDecimal(item.value).subtract(minValue)
                    .divide(span, java.math.MathContext.DECIMAL64).toFloat().coerceIn(0f, 1f)
            return Offset(inset + xRatio * graphWidth, inset + (1f - yRatio) * graphHeight)
        }
        drawLine(Color.Gray, Offset(inset, inset), Offset(inset, inset + graphHeight), 1.dp.toPx())
        drawLine(Color.Gray, Offset(inset, inset + graphHeight),
            Offset(inset + graphWidth, inset + graphHeight), 1.dp.toPx())
        for (eye in listOf(Eye.LEFT, Eye.RIGHT)) {
            val items = numeric.filter { it.eye == eye }.sortedWith(
                compareBy<Reading> { it.recordedAtMillis }.thenBy { it.id },
            )
            val color = if (eye == Eye.LEFT) leftColor else rightColor
            items.zipWithNext().forEach { (before, after) ->
                drawLine(color, point(before), point(after), 2.dp.toPx())
            }
            items.forEach { item ->
                val center = point(item)
                if (eye == Eye.LEFT) {
                    drawCircle(color, 5.dp.toPx(), center)
                } else {
                    val half = 5.dp.toPx()
                    drawRect(color, Offset(center.x - half, center.y - half),
                        Size(half * 2, half * 2))
                }
            }
        }
    }
    val localZone = java.time.ZoneId.systemDefault()
    val first = formatInstant(minTime, localZone)
    val last = formatInstant(maxTime, localZone)
    Text(stringResource(R.string.history_graph_time_span, first, last, localZone.id))
    Text(stringResource(R.string.history_graph_overlap))
}

@Composable
internal fun HistoryReadingDetail(
    reading: Reading,
    sitting: Sitting?,
    busy: Boolean,
    onCorrect: () -> Unit,
    onUndo: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    Text(stringResource(R.string.history_detail_title), modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.eye_summary, eyeLabel(reading.eye)))
    Text(stringResource(R.string.reading_summary, readingLabel(reading.value, reading.rangeState)))
    Text(stringResource(R.string.history_recorded_at, formatReadingTime(reading)))
    if (reading.timeZoneId == null) Text(stringResource(R.string.history_legacy_zone))
    reading.note?.let { Text(stringResource(R.string.note_summary, it)) }
    if (sitting != null) {
        val zone = readingZone(reading)
        Text(stringResource(R.string.history_sitting_started, formatInstant(sitting.startedAtMillis, zone)))
        sitting.finishedAtMillis?.let {
            Text(stringResource(R.string.history_sitting_finished, formatInstant(it, zone)))
        }
    }
    Text(stringResource(R.string.history_revision, reading.revision))
    Secondary(R.string.correct_reading, busy, onCorrect)
    if (reading.revision > 1) {
        Secondary(R.string.undo_correction, busy, onUndo)
    }
    Secondary(R.string.delete_reading, busy, onDelete)
    Secondary(R.string.back, busy, onBack)
}

private fun formatReadingTime(reading: Reading): String =
    formatInstant(reading.recordedAtMillis, readingZone(reading)) +
        " " + readingZone(reading).id

private fun formatInstant(epochMillis: Long, zone: java.time.ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))
