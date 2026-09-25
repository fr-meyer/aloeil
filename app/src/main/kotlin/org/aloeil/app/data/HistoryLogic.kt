package org.aloeil.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** A legacy reading without a stored zone is shown in UTC, with that fallback labelled in the UI. */
fun readingZone(reading: Reading): ZoneId = ZoneId.of(reading.timeZoneId ?: "UTC")

fun readingLocalDate(reading: Reading): LocalDate =
    Instant.ofEpochMilli(reading.recordedAtMillis).atZone(readingZone(reading)).toLocalDate()

data class HistoryFilterResult(
    val readings: List<Reading>,
    val invalidDate: Boolean,
)

/** Invalid date input never silently hides records. */
fun filterHistory(
    readings: List<Reading>,
    eye: Eye?,
    fromText: String,
    toText: String,
): HistoryFilterResult {
    fun parse(input: String): LocalDate? =
        if (input.isBlank()) null else try {
            LocalDate.parse(input.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    val from = parse(fromText)
    val to = parse(toText)
    val invalid = (fromText.isNotBlank() && from == null) ||
        (toText.isNotBlank() && to == null) ||
        (from != null && to != null && from.isAfter(to))
    return HistoryFilterResult(
        readings.filter { reading ->
            (eye == null || reading.eye == eye) &&
                (invalid || from == null || !readingLocalDate(reading).isBefore(from)) &&
                (invalid || to == null || !readingLocalDate(reading).isAfter(to))
        }.sortedWith(compareByDescending<Reading> { it.recordedAtMillis }.thenByDescending { it.id }),
        invalid,
    )
}
