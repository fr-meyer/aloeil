package org.aloeil.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.DataOutputStream
import java.io.EOFException
import java.io.UTFDataFormatException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.aloeil.app.data.ArchiveBundle
import org.aloeil.app.data.ArchiveCodec
import org.aloeil.app.data.ArchiveMaterializationLimitException
import org.aloeil.app.data.BoundedArchiveBuffer
import org.aloeil.app.data.Reading
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic authenticated wire fixtures; never construct a large expected model graph. */
@RunWith(AndroidJUnit4::class)
class ArchiveMaterializationBudgetDeviceTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun fixture(version: Int = 4, write: (DataOutputStream) -> Unit): ByteArray {
        val secret = "synthetic-budget-only".toCharArray()
        val plain = BoundedArchiveBuffer(16 * 1024 * 1024)
        try {
            DataOutputStream(plain).use(write)
            val magic = "ALOEIL0$version".toByteArray(Charsets.US_ASCII)
            val salt = ByteArray(16) { it.toByte() }
            val nonce = ByteArray(12) { (it + 16).toByte() }
            val spec = PBEKeySpec(secret, salt, 210_000, 256)
            val key = try {
                SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
            } finally {
                spec.clearPassword()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(magic)
            val encryptedLength = plain.size() + 16
            val archive = ByteArray(44 + encryptedLength)
            ByteBuffer.wrap(archive).put(magic).putInt(version).put(salt).put(nonce).putInt(encryptedLength)
            check(plain.encryptInto(cipher, archive, 44) == encryptedLength)
            return archive
        } finally {
            secret.fill('\u0000')
            plain.wipe()
        }
    }

    private fun decode(bytes: ByteArray): ArchiveBundle {
        val secret = "synthetic-budget-only".toCharArray()
        return try { ArchiveCodec.decode(bytes, secret) } finally { secret.fill('\u0000') }
    }

    private fun payload(out: DataOutputStream, note: Boolean) {
        out.writeUTF("s0")
        out.writeLong(1000)
        out.writeUTF("LEFT")
        out.writeUTF("0")
        out.writeUTF("") // range
        out.writeUTF("") // zone
        out.writeBoolean(note)
        if (note) out.writeUTF("synthetic")
        out.writeBoolean(false) // no audit fields in this historical-compatible fixture
    }

    /** Each corrected reading has exactly one prior version and operation; all
     * readings link to s0, other closed sittings are harmless retained history,
     * and distinct d-prefixed tombstones cannot conflict with r-prefixed records.
     */
    private fun sections(readings: Int, sittings: Int, versions: Int, deleted: Int,
        firstNote: Boolean = false): ByteArray = fixture { out ->
        out.writeInt(readings)
        repeat(readings) { index ->
            out.writeUTF("r${index.toString(36)}")
            payload(out, firstNote && index == 0)
            out.writeLong(if (index < versions) 2L else 1L)
        }
        out.writeInt(sittings)
        repeat(sittings) { index ->
            out.writeUTF("s${index.toString(36)}")
            out.writeLong(1000)
            out.writeBoolean(true)
            out.writeLong(1001)
        }
        out.writeInt(versions)
        repeat(versions) { index ->
            out.writeUTF("r${index.toString(36)}")
            out.writeLong(1)
            payload(out, false)
        }
        out.writeInt(versions)
        repeat(versions) { index ->
            out.writeUTF("o${index.toString(36)}")
            out.writeUTF("r${index.toString(36)}")
            out.writeLong(2)
        }
        out.writeInt(deleted)
        repeat(deleted) { index ->
            out.writeUTF("d${index.toString(36)}")
            out.writeLong(0)
        }
    }

    private fun assertCounts(bytes: ByteArray, readings: Int, sittings: Int, versions: Int, deleted: Int) {
        val before = digest(bytes)
        val bundle = decode(bytes)
        check(bundle.readings.size == readings && bundle.sittings.size == sittings)
        check(bundle.versions.size == versions && bundle.operations.size == versions)
        check(bundle.deleted.size == deleted)
        check(digest(bytes).contentEquals(before))
    }

    private fun assertBoundaryDecodeAndEncode(bytes: ByteArray) {
        val before = digest(bytes)
        val bundle = decode(bytes)
        check(bundle.readings.size == 100_000 && bundle.sittings.size == 100_000)
        check(bundle.versions.size == 50_000 && bundle.operations.size == 50_000)
        check(bundle.deleted.size == 25_000 && digest(bytes).contentEquals(before))
        // Retire the synthetic source buffer before export; keep only the graph
        // that the real decoder returned, rather than construct a second graph.
        bytes.fill(0)
        val secret = "synthetic-budget-only".toCharArray()
        try {
            val exported = ArchiveCodec.encode(bundle, secret)
            exported.fill(0)
            val firstWithNote = bundle.readings.first().copy(note = "synthetic")
            val notedReadings = object : AbstractList<Reading>() {
                override val size = bundle.readings.size
                override fun get(index: Int): Reading =
                    if (index == 0) firstWithNote else bundle.readings[index]
            }
            val failure = runCatching {
                ArchiveCodec.encode(bundle.copy(readings = notedReadings), secret)
            }.exceptionOrNull()
            check(failure is ArchiveMaterializationLimitException)
        } finally { secret.fill('\u0000') }
    }

    @Test
    fun oneHundredThousandReadingsAndSittingsRemainAccepted() {
        val bytes = sections(100_000, 100_000, 0, 0, firstNote = true)
        try { assertCounts(bytes, 100_000, 100_000, 0, 0) } finally { bytes.fill(0) }
    }

    @Test
    fun exactAggregateBudgetIsAcceptedAndOneAdditionalStringIsRejected() {
        // 100k*8 + 100k*2 + 50k*8 + 50k*3 + 25k*2 = 1,600,000.
        val boundary = sections(100_000, 100_000, 50_000, 25_000)
        try { assertBoundaryDecodeAndEncode(boundary) } finally { boundary.fill(0) }
        val over = sections(100_000, 100_000, 50_000, 25_000, firstNote = true)
        try {
            val before = digest(over)
            val failure = runCatching { decode(over) }.exceptionOrNull()
            check(failure is ArchiveMaterializationLimitException)
            check(digest(over).contentEquals(before))
        } finally { over.fill(0) }
    }

    @Test
    fun formerlyAdmittedDenseSectionsAreRejectedWithoutMutatingInput() {
        // The same serializer's small control satisfies complete history validation.
        val control = sections(1, 1, 1, 1)
        try { assertCounts(control, 1, 1, 1, 1) } finally { control.fill(0) }
        val dense = sections(100_000, 100_000, 100_000, 100_000)
        try {
            check(dense.size <= 16 * 1024 * 1024 + 64)
            val before = digest(dense)
            val failure = runCatching { decode(dense) }.exceptionOrNull()
            check(failure is ArchiveMaterializationLimitException)
            check(digest(dense).contentEquals(before))
        } finally { dense.fill(0) }
    }

    private fun <T> unmaterialized(): List<T> = object : AbstractList<T>() {
        override val size = 100_000
        override fun get(index: Int): T = error("Aggregate export gate must run before record access")
    }

    @Test
    fun exportRejectsOverBudgetBeforeAccessingAnyRecord() {
        val bundle = ArchiveBundle(unmaterialized(), unmaterialized(), unmaterialized(), unmaterialized(), unmaterialized())
        val secret = "synthetic-budget-only".toCharArray()
        try {
            val failure = runCatching { ArchiveCodec.encode(bundle, secret) }.exceptionOrNull()
            check(failure is ArchiveMaterializationLimitException)
        } finally { secret.fill('\u0000') }
    }

    @Test
    fun invalidCountsUtfTruncationAndTrailingBytesRejectAuthenticatedFixtures() {
        val writers: List<(DataOutputStream) -> Unit> = listOf(
            { it.writeInt(100_001) },
            { it.writeInt(-1) },
            { out ->
                out.writeInt(0); out.writeInt(1); out.writeShort(2)
                out.writeByte(0xC2); out.writeByte(0x41)
            },
            { out -> out.writeInt(0); out.writeInt(1); out.writeShort(4); out.writeByte(65) },
            { out -> repeat(5) { out.writeInt(0) }; out.writeByte(1) },
        )
        writers.forEachIndexed { index, writer ->
            val bytes = fixture(write = writer)
            try {
                val before = digest(bytes)
                val failure = runCatching { decode(bytes) }.exceptionOrNull()
                when (index) {
                    0, 1 -> check(failure is IllegalArgumentException && failure.message == "Invalid archive item count")
                    2 -> check(failure is UTFDataFormatException && failure.message == "Malformed archive string")
                    3 -> check(failure is EOFException && failure.message == "Truncated archive string")
                    4 -> check(failure is IllegalArgumentException && failure.message == "Unexpected archive data")
                }
                check(digest(bytes).contentEquals(before))
            } finally { bytes.fill(0) }
        }
    }

    @Test
    fun modifiedUtfCompatibilityAndLegacyIntegerFallbackRemainSupported() {
        val overlong = fixture { out ->
            out.writeInt(0); out.writeInt(1)
            out.writeShort(2); out.writeByte(0xC1); out.writeByte(0xA1) // historical readUTF accepts 'a'
            out.writeLong(1000); out.writeBoolean(false)
            repeat(3) { out.writeInt(0) }
        }
        try { check(decode(overlong).sittings.single().id == "a") } finally { overlong.fill(0) }
        val legacy = fixture(version = 1) { out ->
            out.writeInt(1); out.writeUTF("synthetic-legacy"); out.writeUTF("synthetic-sitting")
            out.writeLong(1000); out.writeUTF("LEFT"); out.writeInt(123); out.writeLong(7)
        }
        try {
            val restored = decode(legacy)
            check(restored.readings.single().value == "12.3" && restored.readings.single().revision == 1L)
        } finally { legacy.fill(0) }
    }

    @Test
    fun authenticationFailurePrecedesAnyBudgetErrorAndLeavesInputUnchanged() {
        val bytes = fixture { it.writeInt(100_001) }
        try {
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            val before = digest(bytes)
            check(runCatching { decode(bytes) }.exceptionOrNull() is AEADBadTagException)
            check(digest(bytes).contentEquals(before))
        } finally { bytes.fill(0) }
    }
}
