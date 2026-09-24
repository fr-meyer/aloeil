package org.aloeil.app.data

import java.util.UUID

enum class Eye { LEFT, RIGHT }

/** Exact normalized decimal text in mmHg; no clinical meaning is added. */
data class Reading(
    val id: String,
    val sittingId: String,
    val recordedAtMillis: Long,
    val eye: Eye,
    val value: String,
    val revision: Long,
    val replicaConfirmedRevision: Long,
) {
    val backupState: BackupState
        get() = if (replicaConfirmedRevision >= revision) BackupState.CONFIRMED else BackupState.PENDING
}

enum class BackupState { PENDING, CONFIRMED }

fun newSittingId(): String = UUID.randomUUID().toString()
fun newReadingId(): String = UUID.randomUUID().toString()
fun newOperationId(): String = UUID.randomUUID().toString()
