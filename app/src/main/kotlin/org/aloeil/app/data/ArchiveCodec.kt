package org.aloeil.app.data

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.UTFDataFormatException
import java.math.BigDecimal
import java.nio.ByteBuffer
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

/** Authenticated input exceeds the work policy; this does not certify its remaining contents. */
class ArchiveMaterializationLimitException : IllegalArgumentException("Archive allocation budget exceeded")

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
    // Admission units count model constructions and UTF string fields, not heap bytes.
    // A 100k-reading + 100k-sitting baseline costs at most 1.1m units, including
    // notes. Dense correction/tombstone combinations can exceed this aggregate cap.
    private const val maxMaterializationUnits = 16L * maxItems
    private const val headerBytes = 44
    private const val iterations = 210_000
    private val random = SecureRandom()

    fun encode(bundle: ArchiveBundle, passphrase: CharArray): ByteArray {
        require(passphrase.size in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) {
            "Export passphrase length is invalid"
        }
        checkExportMaterializationBudget(bundle)
        validate(bundle)
        val bytes = BoundedArchiveBuffer(maxBytes)
        return try {
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
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
            cipher.updateAAD(magicV4)
            // GCM's existing 128-bit tag adds exactly 16 bytes to this plaintext.
            val encryptedLength = bytes.size() + 16
            val archive = ByteArray(headerBytes + encryptedLength)
            ByteBuffer.wrap(archive).put(magicV4).putInt(version).put(salt).put(nonce)
                .putInt(encryptedLength)
            check(bytes.encryptInto(cipher, archive, headerBytes) == encryptedLength) {
                "Unexpected encrypted archive length"
            }
            archive
        } finally {
            bytes.wipe()
        }
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        // The validated slice belongs to the caller. Read it directly without
        // copying or wiping it; parsing starts only after GCM authenticates it.
        val plain = cipher.doFinal(archive, archive.size - length, length)
        return try {
            val result = when (archiveVersion) {
                1 -> decodeLegacy(plain)
                2, 3, 4 -> {
                    preflightStructured(plain, archiveVersion)
                    decodeStructured(plain, archiveVersion)
                }
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
        // Preserve the historical string/integer fallback, but scan each candidate
        // before allocating its list, decoded strings or generated sittings.
        preflightLegacy(plain, stringValues)
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

    private fun checkExportMaterializationBudget(bundle: ArchiveBundle) {
        require(bundle.readings.size <= maxItems && bundle.sittings.size <= maxItems &&
            bundle.versions.size <= maxItems && bundle.operations.size <= maxItems &&
            bundle.deleted.size <= maxItems) { "Too many archive items" }
        // v4: reading/version each constructs a payload and outer model, and reads
        // six UTF fields without a note; other sections have one model + 1/2 UTFs.
        val minimum = bundle.readings.size.toLong() * 8 + bundle.sittings.size.toLong() * 2 +
            bundle.versions.size.toLong() * 8 + bundle.operations.size.toLong() * 3 +
            bundle.deleted.size.toLong() * 2
        if (minimum > maxMaterializationUnits) throw ArchiveMaterializationLimitException()
        val withNotes = minimum + bundle.readings.count { it.note != null } +
            bundle.versions.count { it.payload.note != null }
        if (withNotes > maxMaterializationUnits) throw ArchiveMaterializationLimitException()
    }

    /** Scan authenticated bytes only. This creates no record lists or decoded strings.
     * The explicit work cap narrows extreme multi-section admission; it is neither
     * an object-size calculation nor a guarantee about an Android heap/provider.
     */
    private class Preflight(private val bytes: ByteArray) {
        private var position = 0
        private var units = 0L
        private var utfCodeUnits = 0L

        fun charge(amount: Long) {
            units += amount
            if (units > maxMaterializationUnits) throw ArchiveMaterializationLimitException()
        }

        private fun byte(): Int {
            if (position == bytes.size) throw EOFException("Truncated archive")
            return bytes[position++].toInt() and 255
        }

        fun count(modelUnits: Int): Int {
            val value = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
            require(value in 0..maxItems) { "Invalid archive item count" }
            charge(value.toLong() * modelUnits)
            return value
        }

        fun skip(length: Int) {
            if (length > bytes.size - position) throw EOFException("Truncated archive")
            position += length
        }

        fun boolean(): Boolean = byte() != 0

        fun utf(maximumCharacters: Int = 65_535) {
            charge(1)
            val length = (byte() shl 8) or byte()
            if (length > bytes.size - position) throw EOFException("Truncated archive string")
            val end = position + length
            var characters = 0
            // Match DataInputStream's modified UTF acceptance, including its
            // accepted noncanonical NUL/overlong forms; do not invent a new codec.
            while (position < end) {
                val first = bytes[position].toInt() and 255
                val width = when {
                    first <= 127 -> 1
                    first in 192..223 -> 2
                    first in 224..239 -> 3
                    else -> throw UTFDataFormatException("Malformed archive string")
                }
                if (width > end - position) throw UTFDataFormatException("Malformed archive string")
                for (offset in 1 until width) {
                    if ((bytes[position + offset].toInt() and 192) != 128) {
                        throw UTFDataFormatException("Malformed archive string")
                    }
                }
                position += width
                characters++
            }
            require(characters <= maximumCharacters) { "Archive string is too long" }
            utfCodeUnits += characters
            require(utfCodeUnits <= maxBytes.toLong()) { "Too much archive string data" }
        }

        fun payload(archiveVersion: Int) {
            utf() // sitting ID
            skip(8)
            utf() // eye
            utf(ReadingValue.MAX_LENGTH)
            if (archiveVersion >= 3) {
                utf() // range state
                utf() // zone ID
                if (boolean()) utf(1000)
                if (boolean()) skip(16)
            }
        }

        fun finished() {
            require(position == bytes.size) { "Unexpected archive data" }
        }
    }

    private fun preflightStructured(plain: ByteArray, archiveVersion: Int) {
        val scan = Preflight(plain)
        repeat(scan.count(2)) { scan.utf(); scan.payload(archiveVersion); scan.skip(8) }
        repeat(scan.count(1)) { scan.utf(); scan.skip(8); if (scan.boolean()) scan.skip(8) }
        repeat(scan.count(2)) { scan.utf(); scan.skip(8); scan.payload(archiveVersion) }
        repeat(scan.count(1)) { scan.utf(); scan.utf(); scan.skip(8) }
        if (archiveVersion >= 4) repeat(scan.count(1)) { scan.utf(); scan.skip(8) }
        scan.finished()
    }

    private fun preflightLegacy(plain: ByteArray, stringValues: Boolean) {
        val scan = Preflight(plain)
        // Reserve one Reading and at most one generated Sitting per legacy row.
        repeat(scan.count(2)) {
            scan.utf(); scan.utf(); scan.skip(8); scan.utf()
            if (stringValues) scan.utf() else {
                scan.skip(4)
                scan.charge(2) // numeric conversion and its result string
            }
            scan.skip(8)
        }
        scan.finished()
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
