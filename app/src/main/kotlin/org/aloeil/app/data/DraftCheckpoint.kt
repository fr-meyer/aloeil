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
)

internal object DraftCodec {
    fun encode(draft: DraftCheckpoint): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeUTF(draft.sittingId)
            out.writeUTF(draft.readingId)
            out.writeUTF(draft.step)
            out.writeUTF(draft.eye?.name.orEmpty())
            out.writeUTF(draft.input)
            out.writeUTF(draft.focusedControl)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): DraftCheckpoint {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1) { "Unsupported draft version" }
        val draft = DraftCheckpoint(
            sittingId = input.readUTF(),
            readingId = input.readUTF(),
            step = input.readUTF(),
            eye = input.readUTF().takeIf { it.isNotEmpty() }?.let(Eye::valueOf),
            input = input.readUTF(),
            focusedControl = input.readUTF(),
        )
        require(input.available() == 0) { "Unexpected draft data" }
        require(draft.sittingId.isNotBlank() && draft.readingId.isNotBlank()) { "Invalid draft" }
        return draft
    }
}
