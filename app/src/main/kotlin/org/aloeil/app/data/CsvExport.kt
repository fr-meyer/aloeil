package org.aloeil.app.data

import java.io.Writer
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Human-readable current-facts snapshot. Encrypted archives remain the lossless restore format. */
object CsvExport {
    const val fileName = "aloeil-readings.csv"
    const val mimeType = "text/csv"

    val columns = listOf(
        "aloeil_csv_version", "reading_id", "sitting_id",
        "sitting_started_at_utc", "sitting_finished_at_utc",
        "recorded_at_utc", "recorded_time_zone", "eye", "reading_kind",
        "value_mmhg", "note", "revision", "created_at_utc", "updated_at_utc",
    )

    fun write(readings: List<Reading>, sittings: List<Sitting>, output: Writer) {
        val groups = sittings.associateBy { it.id }
        require(groups.size == sittings.size) { "Duplicate sitting ID" }
        output.write(columns.joinToString(","))
        output.write("\r\n")
        readings.sortedWith(
            compareBy<Reading> { it.recordedAtMillis }.thenBy { it.id },
        ).forEach { reading ->
            val sitting = groups[reading.sittingId]
                ?: throw IllegalArgumentException("Reading has no sitting")
            val row = listOf(
                "1",
                spreadsheetSafe(reading.id),
                spreadsheetSafe(reading.sittingId),
                instant(sitting.startedAtMillis),
                sitting.finishedAtMillis?.let(::instant).orEmpty(),
                instant(reading.recordedAtMillis),
                spreadsheetSafe(reading.timeZoneId.orEmpty()),
                reading.eye.name,
                reading.rangeState?.name ?: "NUMERIC",
                if (reading.rangeState == null) spreadsheetSafe(reading.value) else "",
                spreadsheetSafe(reading.note.orEmpty()),
                reading.revision.toString(),
                reading.createdAtMillis?.let(::instant).orEmpty(),
                reading.updatedAtMillis?.let(::instant).orEmpty(),
            )
            output.write(row.joinToString(",") { csvCell(it) })
            output.write("\r\n")
        }
        output.flush()
    }

    private fun instant(value: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(value))

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    /** A leading apostrophe prevents common spreadsheet programs from evaluating text cells. */
    private fun spreadsheetSafe(value: String): String =
        if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
}
