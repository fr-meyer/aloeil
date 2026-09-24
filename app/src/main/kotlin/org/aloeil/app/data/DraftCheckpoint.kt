package org.aloeil.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Protected, unsaved capture state. A caller reserves the reading ID before the first save. */
data class DraftCheckpoint(
    val sittingId: String,
    val readingId: String,
    val step: String,
    val eye: Eye?,
    val input: String,
    val focusedControl: String,
    val baseRevision: Long? = null,
    val rangeState: RangeState? = null,
    val note: String = "",
)

internal object DraftCodec {
    fun encode(draft: DraftCheckpoint): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(3)
            out.writeUTF(draft.sittingId)
            out.writeUTF(draft.readingId)
            out.writeUTF(draft.step)
            out.writeUTF(draft.eye?.name.orEmpty())
            out.writeUTF(draft.input)
            out.writeUTF(draft.focusedControl)
            out.writeLong(draft.baseRevision ?: 0)
            out.writeUTF(draft.rangeState?.name.orEmpty())
            out.writeUTF(draft.note)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): DraftCheckpoint {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(version in 1..3) { "Unsupported draft version" }
        val draft = DraftCheckpoint(
            sittingId = input.readUTF(),
            readingId = input.readUTF(),
            step = input.readUTF(),
            eye = input.readUTF().takeIf { it.isNotEmpty() }?.let(Eye::valueOf),
            input = input.readUTF(),
            focusedControl = input.readUTF(),
            baseRevision = if (version >= 2) input.readLong().takeIf { it > 0 } else null,
            rangeState = if (version >= 3) input.readUTF().takeIf { it.isNotEmpty() }?.let(RangeState::valueOf) else null,
            note = if (version >= 3) input.readUTF() else "",
        )
        require(draft.note.length <= 1000) { "Invalid draft note" }
        require(input.available() == 0) { "Unexpected draft data" }
        require(draft.sittingId.isNotBlank() && draft.readingId.isNotBlank()) { "Invalid draft" }
        return draft
    }
}

/** Decide the resume screen from a persisted checkpoint and the committed row. */
fun restoredDraftStep(draft: DraftCheckpoint, committed: Reading?): String {
    if (committed == null) return draft.step
    if (draft.step in setOf("EYE", "VALUE", "NOTE", "REVIEW")) return "SAVED"
    if (draft.step.startsWith("CORRECT_") &&
        draft.baseRevision != null && committed.revision > draft.baseRevision
    ) return "CORRECT_SAVED"
    return draft.step
}
