package org.aloeil.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** All fields that describe the health event are inside the encrypted payload. */
internal data class ReadingPayload(
    val sittingId: String,
    val recordedAtMillis: Long,
    val eye: Eye,
    val value: String,
)

internal object ReadingPayloadCodec {
    fun encode(payload: ReadingPayload): ByteArray = ByteArrayOutputStream().also { bytes ->
        require(payload.sittingId.isNotBlank())
        require(ReadingValue.parse(payload.value) == ReadingValueResult.Valid(payload.value))
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeUTF(payload.sittingId)
            out.writeLong(payload.recordedAtMillis)
            out.writeUTF(payload.eye.name)
            out.writeUTF(payload.value)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): ReadingPayload {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1) { "Unsupported reading payload version" }
        val result = ReadingPayload(
            input.readUTF(),
            input.readLong(),
            Eye.valueOf(input.readUTF()),
            input.readUTF(),
        )
        require(input.available() == 0 && result.sittingId.isNotBlank()) { "Invalid reading payload" }
        require(ReadingValue.parse(result.value) == ReadingValueResult.Valid(result.value)) {
            "Invalid reading value"
        }
        return result
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
        require(input.available() == 0) { "Invalid sitting payload" }
        return Sitting(id, started, finished)
    }
}
