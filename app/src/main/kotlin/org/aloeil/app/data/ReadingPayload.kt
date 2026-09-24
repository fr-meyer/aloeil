package org.aloeil.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** All fields that describe the health event are inside the encrypted payload. */
data class ReadingPayload(
    val sittingId: String,
    val recordedAtMillis: Long,
    val eye: Eye,
    val value: String,
    val rangeState: RangeState? = null,
    val timeZoneId: String? = null,
    val note: String? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
)

internal fun Reading.asPayload(): ReadingPayload = ReadingPayload(
    sittingId, recordedAtMillis, eye, value, rangeState, timeZoneId,
    note, createdAtMillis, updatedAtMillis,
)

internal fun ReadingPayload.asReading(
    id: String,
    revision: Long,
    replicaConfirmedRevision: Long,
): Reading = Reading(
    id, sittingId, recordedAtMillis, eye, value, revision, replicaConfirmedRevision,
    rangeState, timeZoneId, note, createdAtMillis, updatedAtMillis,
)

internal object ReadingPayloadCodec {
    fun encode(payload: ReadingPayload): ByteArray = ByteArrayOutputStream().also { bytes ->
        validate(payload)
        DataOutputStream(bytes).use { out ->
            out.writeInt(2)
            out.writeUTF(payload.sittingId)
            out.writeLong(payload.recordedAtMillis)
            out.writeUTF(payload.eye.name)
            out.writeUTF(payload.value)
            out.writeUTF(payload.rangeState?.name.orEmpty())
            out.writeUTF(payload.timeZoneId.orEmpty())
            out.writeBoolean(payload.note != null)
            payload.note?.let(out::writeUTF)
            out.writeBoolean(payload.createdAtMillis != null)
            if (payload.createdAtMillis != null && payload.updatedAtMillis != null) {
                out.writeLong(payload.createdAtMillis)
                out.writeLong(payload.updatedAtMillis)
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): ReadingPayload {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(version == 1 || version == 2) { "Unsupported reading payload version" }
        val sittingId = input.readUTF()
        val recordedAtMillis = input.readLong()
        val eye = Eye.valueOf(input.readUTF())
        val value = input.readUTF()
        val result = if (version == 1) {
            ReadingPayload(sittingId, recordedAtMillis, eye, value)
        } else {
            val rangeState = input.readUTF().takeIf { it.isNotEmpty() }?.let(RangeState::valueOf)
            val timeZoneId = input.readUTF().takeIf { it.isNotEmpty() }
            val note = if (input.readBoolean()) input.readUTF() else null
            val hasAudit = input.readBoolean()
            ReadingPayload(
                sittingId, recordedAtMillis, eye, value, rangeState, timeZoneId, note,
                if (hasAudit) input.readLong() else null,
                if (hasAudit) input.readLong() else null,
            )
        }
        require(input.available() == 0) { "Unexpected reading payload data" }
        validate(result)
        return result
    }

    private fun validate(payload: ReadingPayload) {
        require(payload.sittingId.isNotBlank()) { "Invalid sitting ID" }
        ReadingFact.validate(
            payload.value, payload.rangeState, payload.timeZoneId,
            payload.note, payload.createdAtMillis, payload.updatedAtMillis,
        )
    }
}

data class Sitting(
    val id: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
)

internal object SittingPayloadCodec {
    fun encode(sitting: Sitting): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeLong(sitting.startedAtMillis)
            out.writeBoolean(sitting.finishedAtMillis != null)
            sitting.finishedAtMillis?.let(out::writeLong)
        }
    }.toByteArray()

    fun decode(id: String, bytes: ByteArray): Sitting {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1) { "Unsupported sitting payload version" }
        val started = input.readLong()
        val finished = if (input.readBoolean()) input.readLong() else null
        require(finished == null || finished >= started) { "Invalid sitting times" }
        require(input.available() == 0) { "Invalid sitting payload" }
        return Sitting(id, started, finished)
    }
}
