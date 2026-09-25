package org.aloeil.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ArchivedVersion(
    val readingId: String,
    val revision: Long,
    val payload: ReadingPayload,
)

data class ArchivedOperation(
    val id: String,
    val readingId: String,
    val resultingRevision: Long,
)

data class ArchiveBundle(
    val readings: List<Reading>,
    val sittings: List<Sitting>,
    val versions: List<ArchivedVersion>,
    val operations: List<ArchivedOperation>,
    val deleted: List<DeletedReadingRow> = emptyList(),
)

/** Authenticated portable backup. The Android Keystore key never leaves the phone. */
object ArchiveCodec {
    const val MIN_PASSPHRASE_LENGTH = 12
    const val MAX_PASSPHRASE_LENGTH = 1024
    private val magicV4 = "ALOEIL04".toByteArray(Charsets.US_ASCII)
    private val magicV3 = "ALOEIL03".toByteArray(Charsets.US_ASCII)
    private val magicV2 = "ALOEIL02".toByteArray(Charsets.US_ASCII)
    private val magicV1 = "ALOEIL01".toByteArray(Charsets.US_ASCII)
    private const val version = 4
    private const val maxBytes = 16 * 1024 * 1024
    private const val maxItems = 100_000
    private const val iterations = 210_000
    private val random = SecureRandom()

    private class BoundedPlainBuffer : ByteArrayOutputStream() {
        override fun write(b: Int) {
            require(size() < maxBytes) { "Archive is too large" }
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            require(len <= maxBytes - size()) { "Archive is too large" }
            super.write(b, off, len)
        }

        fun wipe() { buf.fill(0) }
    }

    fun encode(bundle: ArchiveBundle, passphrase: CharArray): ByteArray {
        require(passphrase.size in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) {
            "Export passphrase length is invalid"
        }
        validate(bundle)
        val bytes = BoundedPlainBuffer()
        val plain = try {
            DataOutputStream(bytes).use { out ->
                out.writeInt(bundle.readings.size)
                bundle.readings.forEach { out.writeReadingV3(it) }
                out.writeInt(bundle.sittings.size)
                bundle.sittings.forEach { sitting ->
                    out.writeUTF(sitting.id)
                    out.writeLong(sitting.startedAtMillis)
                    out.writeBoolean(sitting.finishedAtMillis != null)
                    sitting.finishedAtMillis?.let(out::writeLong)
                }
                out.writeInt(bundle.versions.size)
                bundle.versions.forEach { item ->
                    out.writeUTF(item.readingId)
                    out.writeLong(item.revision)
                    out.writePayloadV3(item.payload)
                }
                out.writeInt(bundle.operations.size)
                bundle.operations.forEach { item ->
                    out.writeUTF(item.id)
                    out.writeUTF(item.readingId)
                    out.writeLong(item.resultingRevision)
                }
                out.writeInt(bundle.deleted.size)
                bundle.deleted.forEach { item ->
                    out.writeUTF(item.id)
                    out.writeLong(0L) // Reserved v4 field; deletion time is never retained.
                }
            }
            bytes.toByteArray()
        } finally {
            bytes.wipe()
        }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
            cipher.updateAAD(magicV4)
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(magicV4)
                out.writeInt(version)
                out.write(salt)
                out.write(nonce)
                out.writeInt(encrypted.size)
                out.write(encrypted)
            }
        }.toByteArray()
    }

    /** Version 1 archives are migrated additively; they had no sitting or correction history. */
    fun decode(archive: ByteArray, passphrase: CharArray): ArchiveBundle {
        require(passphrase.isNotEmpty()) { "An export passphrase is required" }
        require(archive.size in 57..maxBytes + 64) { "Invalid archive size" }
        val input = DataInputStream(ByteArrayInputStream(archive))
        val magic = ByteArray(8).also(input::readFully)
        val archiveVersion = input.readInt()
        require(
            (magic.contentEquals(magicV4) && archiveVersion == version) ||
                (magic.contentEquals(magicV3) && archiveVersion == 3) ||
                (magic.contentEquals(magicV2) && archiveVersion == 2) ||
                (magic.contentEquals(magicV1) && archiveVersion == 1),
        ) { "Unsupported Aloeil archive" }
        val salt = ByteArray(16).also(input::readFully)
        val nonce = ByteArray(12).also(input::readFully)
        val length = input.readInt()
        require(length in 16..maxBytes + 16 && length == input.available()) { "Invalid archive length" }
        val encrypted = ByteArray(length).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        val plain = try {
            cipher.doFinal(encrypted)
        } finally {
            encrypted.fill(0)
        }
        return try {
            val result = when (archiveVersion) {
                1 -> decodeLegacy(plain)
                2, 3, 4 -> decodeStructured(plain, archiveVersion)
                else -> error("Unsupported archive version")
            }
            validate(result, requireCompleteHistory = archiveVersion != 1)
            result
        } finally {
            plain.fill(0)
        }
    }

    private fun decodeStructured(plain: ByteArray, archiveVersion: Int): ArchiveBundle {
        val input = DataInputStream(ByteArrayInputStream(plain))
        val readings = List(input.readCount()) {
            if (archiveVersion >= 3) input.readReadingV3() else input.readReadingV2()
        }
        val sittings = List(input.readCount()) {
            val id = input.readUTF()
            val started = input.readLong()
            val finished = if (input.readBoolean()) input.readLong() else null
            Sitting(id, started, finished)
        }
        val versions = List(input.readCount()) {
            ArchivedVersion(
                input.readUTF(), input.readLong(),
                if (archiveVersion >= 3) input.readPayloadV3() else input.readPayloadV2(),
            )
        }
        val operations = List(input.readCount()) {
            ArchivedOperation(input.readUTF(), input.readUTF(), input.readLong())
        }
        val deleted = if (archiveVersion >= 4) List(input.readCount()) {
            val id = input.readUTF()
            input.readLong() // Discard the legacy v4 deletion timestamp.
            DeletedReadingRow(id)
        } else emptyList()
        require(input.available() == 0) { "Unexpected archive data" }
        return ArchiveBundle(readings, sittings, versions, operations, deleted)
    }

    private fun decodeLegacy(plain: ByteArray): ArchiveBundle {
        val readings = runCatching { readLegacyReadings(plain, stringValues = true) }
            .getOrElse { readLegacyReadings(plain, stringValues = false) }
        val sittings = readings.groupBy { it.sittingId }.map { (id, values) ->
            Sitting(id, values.minOf { it.recordedAtMillis }, values.maxOf { it.recordedAtMillis })
        }
        return ArchiveBundle(readings, sittings, emptyList(), emptyList())
    }

    private fun readLegacyReadings(plain: ByteArray, stringValues: Boolean): List<Reading> {
        val input = DataInputStream(ByteArrayInputStream(plain))
        val readings = List(input.readCount()) {
            val id = input.readUTF()
            val sittingId = input.readUTF()
            val time = input.readLong()
            val eye = Eye.valueOf(input.readUTF())
            val value = if (stringValues) input.readUTF() else {
                BigDecimal(input.readInt()).movePointLeft(1).toPlainString()
            }
            val legacyRevision = input.readLong()
            require(legacyRevision > 0)
            // A v1 file contains only the latest value. Treat it as the restored baseline
            // so future corrections and v2 exports cannot claim missing undo history.
            Reading(id, sittingId, time, eye, value, 1, 0)
        }
        require(input.available() == 0) { "Unexpected legacy archive data" }
        return readings
    }

    private fun validate(bundle: ArchiveBundle, requireCompleteHistory: Boolean = true) {
        require(
            bundle.readings.size <= maxItems && bundle.sittings.size <= maxItems &&
                bundle.versions.size <= maxItems && bundle.operations.size <= maxItems &&
                bundle.deleted.size <= maxItems,
        ) { "Too many archive items" }
        require(bundle.readings.map { it.id }.toSet().size == bundle.readings.size) {
            "Duplicate reading ID"
        }
        require(bundle.sittings.map { it.id }.toSet().size == bundle.sittings.size) {
            "Duplicate sitting ID"
        }
        require(bundle.versions.map { it.readingId to it.revision }.toSet().size == bundle.versions.size) {
            "Duplicate reading revision"
        }
        require(bundle.operations.map { it.id }.toSet().size == bundle.operations.size) {
            "Duplicate correction operation"
        }
        require(bundle.deleted.map { it.id }.toSet().size == bundle.deleted.size) {
            "Duplicate deleted reading ID"
        }
        val readings = bundle.readings.associateBy { it.id }
        bundle.deleted.forEach {
            require(it.id.isNotBlank() && it.id !in readings) { "Deleted ID conflicts with active reading" }
        }
        val sittings = bundle.sittings.map { it.id }.toSet()
        bundle.readings.forEach {
            require(it.id.isNotBlank() && it.sittingId in sittings && it.revision > 0)
            ReadingFact.validate(
                it.value, it.rangeState, it.timeZoneId, it.note,
                it.createdAtMillis, it.updatedAtMillis,
            )
        }
        bundle.sittings.forEach {
            require(it.id.isNotBlank())
            require(it.finishedAtMillis == null || it.finishedAtMillis >= it.startedAtMillis)
        }
        bundle.versions.forEach {
            val current = readings[it.readingId] ?: error("Orphaned reading revision")
            require(it.revision in 1L until current.revision)
            require(it.payload.sittingId == current.sittingId)
            require(it.payload.recordedAtMillis == current.recordedAtMillis)
            require(it.payload.timeZoneId == current.timeZoneId)
            require(it.payload.createdAtMillis == current.createdAtMillis)
            ReadingFact.validate(
                it.payload.value, it.payload.rangeState, it.payload.timeZoneId,
                it.payload.note, it.payload.createdAtMillis, it.payload.updatedAtMillis,
            )
        }
        bundle.operations.forEach {
            val current = readings[it.readingId] ?: error("Orphaned correction operation")
            require(it.id.isNotBlank() && it.resultingRevision in 2L..current.revision)
        }
        if (requireCompleteHistory) {
            val versionsByReading = bundle.versions.groupBy { it.readingId }
            val operationsByReading = bundle.operations.groupBy { it.readingId }
            bundle.readings.forEach { reading ->
                require(reading.revision in 1L..(maxItems.toLong() + 1)) {
                    "Invalid reading revision"
                }
                val expectedCount = reading.revision - 1
                val prior = versionsByReading[reading.id].orEmpty().sortedBy { it.revision }
                val corrections = operationsByReading[reading.id].orEmpty()
                    .sortedBy { it.resultingRevision }
                require(prior.size.toLong() == expectedCount) {
                    "Incomplete reading revision history"
                }
                require(corrections.size.toLong() == expectedCount) {
                    "Incomplete correction operation history"
                }
                prior.forEachIndexed { index, item ->
                    require(item.revision == index.toLong() + 1) {
                        "Non-contiguous reading revision history"
                    }
                }
                corrections.forEachIndexed { index, item ->
                    require(item.resultingRevision == index.toLong() + 2) {
                        "Non-contiguous correction operation history"
                    }
                }
            }
        }
    }

    private fun DataOutputStream.writeReadingV3(reading: Reading) {
        writeUTF(reading.id)
        writePayloadV3(reading.asPayload())
        writeLong(reading.revision)
    }

    private fun DataInputStream.readReadingV3(): Reading {
        val id = readUTF()
        val payload = readPayloadV3()
        return payload.asReading(id, readLong(), 0)
    }

    private fun DataInputStream.readReadingV2(): Reading {
        val id = readUTF()
        val payload = readPayloadV2()
        return payload.asReading(id, readLong(), 0)
    }

    private fun DataOutputStream.writePayloadV3(payload: ReadingPayload) {
        writeUTF(payload.sittingId)
        writeLong(payload.recordedAtMillis)
        writeUTF(payload.eye.name)
        writeUTF(payload.value)
        writeUTF(payload.rangeState?.name.orEmpty())
        writeUTF(payload.timeZoneId.orEmpty())
        writeBoolean(payload.note != null)
        payload.note?.let { writeUTF(it) }
        writeBoolean(payload.createdAtMillis != null)
        if (payload.createdAtMillis != null && payload.updatedAtMillis != null) {
            writeLong(payload.createdAtMillis)
            writeLong(payload.updatedAtMillis)
        }
    }

    private fun DataInputStream.readPayloadV3(): ReadingPayload {
        val sittingId = readUTF()
        val recordedAtMillis = readLong()
        val eye = Eye.valueOf(readUTF())
        val value = readUTF()
        val rangeState = readUTF().takeIf { it.isNotEmpty() }?.let(RangeState::valueOf)
        val timeZoneId = readUTF().takeIf { it.isNotEmpty() }
        val note = if (readBoolean()) readUTF() else null
        val hasAudit = readBoolean()
        return ReadingPayload(
            sittingId, recordedAtMillis, eye, value, rangeState, timeZoneId, note,
            if (hasAudit) readLong() else null,
            if (hasAudit) readLong() else null,
        )
    }

    private fun DataInputStream.readPayloadV2(): ReadingPayload =
        ReadingPayload(readUTF(), readLong(), Eye.valueOf(readUTF()), readUTF())

    private fun DataInputStream.readCount(): Int = readInt().also {
        require(it in 0..maxItems) { "Invalid archive item count" }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }
}
