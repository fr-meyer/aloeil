package org.aloeil.app.data

import java.time.ZoneId
import java.util.UUID

enum class Eye { LEFT, RIGHT }

/** A device display state is recorded as a fact, without inventing a numeric value. */
enum class RangeState { BELOW_RANGE, ABOVE_RANGE }

/** Exact user-entered fact, with no clinical interpretation. */
data class Reading(
    val id: String,
    val sittingId: String,
    val recordedAtMillis: Long,
    val eye: Eye,
    val value: String,
    val revision: Long,
    val replicaConfirmedRevision: Long,
    val rangeState: RangeState? = null,
    val timeZoneId: String? = null,
    val note: String? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
) {
    val backupState: BackupState
        get() = if (replicaConfirmedRevision >= revision) BackupState.CONFIRMED else BackupState.PENDING
}

enum class BackupState { PENDING, CONFIRMED }

internal object ReadingFact {
    fun validate(
        value: String,
        rangeState: RangeState?,
        timeZoneId: String?,
        note: String?,
        createdAtMillis: Long?,
        updatedAtMillis: Long?,
    ) {
        if (rangeState == null) {
            require(ReadingValue.parse(value) == ReadingValueResult.Valid(value)) {
                "Invalid numeric reading"
            }
        } else {
            require(value.isEmpty()) { "A device range state cannot have a numeric value" }
        }
        timeZoneId?.let { require(it.isNotBlank()); ZoneId.of(it) }
        require(note == null || note.length <= 1000) { "Note is too long" }
        require((createdAtMillis == null) == (updatedAtMillis == null)) {
            "Incomplete audit timestamps"
        }
        if (createdAtMillis != null && updatedAtMillis != null) {
            require(updatedAtMillis >= createdAtMillis) { "Invalid audit timestamps" }
        }
    }
}

fun newSittingId(): String = UUID.randomUUID().toString()
fun newReadingId(): String = UUID.randomUUID().toString()
fun newOperationId(): String = UUID.randomUUID().toString()
