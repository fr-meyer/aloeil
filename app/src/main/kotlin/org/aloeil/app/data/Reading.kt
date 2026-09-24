package org.aloeil.app.data

import java.util.UUID

enum class Eye { LEFT, RIGHT }

/** A value in tenths of the unit shown by the user's tonometer. No clinical meaning is added. */
data class Reading(
    val id: String,
    val sittingId: String,
    val recordedAtMillis: Long,
    val eye: Eye,
    val valueTenths: Int,
    val revision: Long,
    val replicaConfirmedRevision: Long,
) {
    val backupState: BackupState
        get() = if (replicaConfirmedRevision >= revision) BackupState.CONFIRMED else BackupState.PENDING
}

enum class BackupState { PENDING, CONFIRMED }

fun newSittingId(): String = UUID.randomUUID().toString()
fun newReadingId(): String = UUID.randomUUID().toString()
