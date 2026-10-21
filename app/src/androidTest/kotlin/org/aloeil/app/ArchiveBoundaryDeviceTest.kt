package org.aloeil.app

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.ArchiveBundle
import org.aloeil.app.data.ArchiveCodec
import org.aloeil.app.data.BoundedArchiveBuffer
import org.aloeil.app.data.DeletedReadingRow
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android codec/reader/provider calls with synthetic identifiers only. */
@RunWith(AndroidJUnit4::class)
class ArchiveBoundaryDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val plainLimit = 16 * 1024 * 1024
    private val envelopeLimit = plainLimit + 64

    private fun secret() = "synthetic-test-only".toCharArray()

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    // Post-operation heap snapshots are observations, not allocation peaks or a
    // promise that every Android heap/provider can support the same workload.
    private fun observeHeap(phase: String) {
        val runtime = Runtime.getRuntime()
        Log.i("ArchiveBoundaryHeap", "phase=$phase used=${runtime.totalMemory() - runtime.freeMemory()} " +
            "committed=${runtime.totalMemory()} maximum=${runtime.maxMemory()}")
    }

    private fun boundaryBundle(extraByte: Boolean = false): ArchiveBundle {
        // Five v4 counts take 20 bytes; each tombstone adds UTF's 2-byte length
        // and an 8-byte reserved field. All identifiers fit modified UTF's limit.
        val records = List(280) { index ->
            val prefix = "synthetic-boundary-$index-"
            val length = 59_908 + if (index < 156) 1 else 0
            val extra = if (extraByte && index == 279) 1 else 0
            DeletedReadingRow(prefix + "x".repeat(length + extra - prefix.length))
        }
        return ArchiveBundle(emptyList(), emptyList(), emptyList(), emptyList(), records)
    }

    private fun encodeBoundary(passphrase: CharArray): ByteArray =
        ArchiveCodec.encode(boundaryBundle(), passphrase)

    private fun readBoundary(passphrase: CharArray): ByteArray {
        // Do not retain a second large expected graph or ciphertext during decode/import.
        val source = encodeBoundary(passphrase)
        try {
            check(source.size == plainLimit + 60)
            val result = readArchive(ByteArrayInputStream(source))
            check(digest(source).contentEquals(digest(result)))
            return result
        } finally {
            source.fill(0)
        }
    }

    private fun checkBoundaryDecoded(archive: ByteArray, passphrase: CharArray) {
        val bundle = ArchiveCodec.decode(archive, passphrase)
        check(bundle.readings.isEmpty() && bundle.sittings.isEmpty())
        check(bundle.versions.isEmpty() && bundle.operations.isEmpty())
        check(bundle.deleted.size == 280)
        bundle.deleted.forEachIndexed { index, record ->
            val prefix = "synthetic-boundary-$index-"
            check(record.id.startsWith(prefix))
            check(record.id.length == 59_908 + if (index < 156) 1 else 0)
            for (position in prefix.length until record.id.length) check(record.id[position] == 'x')
        }
    }

    @Test
    fun exactPlainLimitReadsPreviewsAndImportsWithoutChangingCallerBytes() = runBlocking {
        val passphrase = secret()
        val database = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        var archive: ByteArray? = null
        try {
            observeHeap("before-boundary-read")
            val bytes = readBoundary(passphrase)
            archive = bytes
            observeHeap("after-boundary-read")
            val fingerprint = digest(bytes)
            checkBoundaryDecoded(bytes, passphrase)
            observeHeap("after-boundary-decode")
            val repository = ReadingRepository(database.readings(), SyntheticCipher())
            val preview = repository.previewArchive(bytes, passphrase)
            check(preview.readingCount == 0 && preview.sittingCount == 0 && preview.deletedCount == 280)
            observeHeap("after-boundary-preview")
            check(repository.importArchive(bytes, passphrase) == 0)
            check(database.readings().allDeletedReadings().size == 280)
            check(repository.all().isEmpty())
            check(digest(bytes).contentEquals(fingerprint))
            observeHeap("after-boundary-import")
        } finally {
            archive?.fill(0)
            passphrase.fill('\u0000')
            database.close()
        }
    }

    @Test
    fun oneByteBeyondPlainLimitIsRejected() {
        val passphrase = secret()
        try {
            val failure = runCatching { ArchiveCodec.encode(boundaryBundle(extraByte = true), passphrase) }
                .exceptionOrNull()
            check(failure is IllegalArgumentException && failure.message == "Archive is too large")
        } finally {
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun readerRejectsOneByteBeyondEnvelopeLimitWithoutAnotherLargeFixture() {
        val input = object : InputStream() {
            var remaining = envelopeLimit + 1
            override fun read(): Int = if (remaining == 0) -1 else { remaining--; 0 }
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining == 0) return -1
                val count = minOf(length, remaining)
                destination.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        val failure = runCatching { readArchive(input) }.exceptionOrNull()
        check(failure is IllegalArgumentException && failure.message == "Archive is too large")
    }

    @Test
    fun boundaryTamperingFailsBeforeChangingAnExistingStore() = runBlocking {
        val passphrase = secret()
        val database = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        var archive: ByteArray? = null
        try {
            val repository = ReadingRepository(database.readings(), SyntheticCipher())
            repository.startSitting("synthetic-existing-sitting")
            repository.record("synthetic-existing-reading", "synthetic-existing-sitting", Eye.LEFT, "12.3")
            val bytes = encodeBoundary(passphrase)
            archive = bytes
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            val fingerprint = digest(bytes)
            check(runCatching { repository.importArchive(bytes, passphrase) }.exceptionOrNull()
                is AEADBadTagException)
            check(repository.all().single().id == "synthetic-existing-reading")
            check(database.readings().allDeletedReadings().isEmpty())
            check(digest(bytes).contentEquals(fingerprint))
        } finally {
            archive?.fill(0)
            passphrase.fill('\u0000')
            database.close()
        }
    }

    @Test
    fun itemLimitRemainsOneHundredThousand() {
        val passphrase = secret()
        var archive: ByteArray? = null
        try {
            val bundle = ArchiveBundle(emptyList(), emptyList(), emptyList(), emptyList(),
                List(100_000) { DeletedReadingRow("synthetic-count-$it") })
            val bytes = ArchiveCodec.encode(bundle, passphrase)
            archive = bytes
            check(ArchiveCodec.decode(bytes, passphrase).deleted.size == 100_000)
            val overflow = bundle.copy(deleted = bundle.deleted + DeletedReadingRow("synthetic-over-count"))
            val failure = runCatching { ArchiveCodec.encode(overflow, passphrase) }.exceptionOrNull()
            check(failure is IllegalArgumentException && failure.message == "Too many archive items")
        } finally {
            archive?.fill(0)
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun versionsOneTwoAndThreeRemainReadableWithoutChangingInput() {
        val passphrase = secret()
        try {
            // Existing fixed synthetic v1/v2 fixtures also run through Android's provider.
            val v1 = Base64.getDecoder().decode(
                "QUxPRUlMMDEAAAABAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwAAAFZ3E05J0ZXhSZwwoSVE54VZX9vyU3fYjy980rw13JmTaM9G7KnRbZgZbjOueHujSU1mGsIVixhajy6Dlr6FS/jOTs+ny00KRUE9oOPCU6zxja5rtSUAqQ==",
            )
            val v2 = Base64.getDecoder().decode(
                "QUxPRUlMMDIAAAACAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwAAAPl3E05J0ZXhSZwwoSVE54VZQI7yU3ffjy980rw13JmTaM9G7KnRbZgZKQKuefHnY8AOGsdDjhpUjy6Clr2FSszOTs+ny00JVKriL3Er1pqtwnzCQfPRGQwHvU6tzxakKEX9ckrcvZjpgoV2gQInsQkBpAZOPsnroLH32UtSoR3JE428AqUE1UhqLJXkSRGBWf6G/vpxpOg5LmklVvB2b1ZtLfZJVY0J3LWYOTwp7PIo81G9jLg2YmSOIOPj4QxFHS3YgABLjzjRu3LL32tlDpvw2INYRKAKTUvhdol7zfibds6nTMLHql08h2mrBbMf9VNp+GNRb50uv4g=",
            )
            listOf(v1, v2, emptyVersionThree(passphrase)).forEachIndexed { index, bytes ->
                try {
                    val fingerprint = digest(bytes)
                    val restored = ArchiveCodec.decode(bytes, passphrase)
                    if (index == 0) check(restored.readings.single().revision == 1L)
                    if (index == 1) check(restored.readings.single().revision == 2L && restored.versions.size == 1)
                    if (index == 2) check(restored == ArchiveBundle(emptyList(), emptyList(), emptyList(), emptyList()))
                    check(digest(bytes).contentEquals(fingerprint))
                } finally {
                    bytes.fill(0)
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    // Historical v3 has four zero counts, unlike v4's five. Construct that tiny
    // authenticated fixture independently of the current encoder under test.
    private fun emptyVersionThree(passphrase: CharArray): ByteArray {
        val magic = "ALOEIL03".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 16).toByte() }
        val spec = PBEKeySpec(passphrase, salt, 210_000, 256)
        val key = try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        val encrypted = cipher.doFinal(ByteArray(16))
        return ByteBuffer.allocate(44 + encrypted.size).put(magic).putInt(3).put(salt).put(nonce)
            .putInt(encrypted.size).put(encrypted).array()
    }

    @Test
    fun readerSizedWritesKeepCapacityWithinTheEnvelopeLimit() {
        val output = BoundedArchiveBuffer(envelopeLimit)
        val chunk = ByteArray(8192) { 7 }
        try {
            var remaining = plainLimit + 60
            while (remaining > 0) {
                val count = minOf(chunk.size, remaining)
                output.write(chunk, 0, count)
                check(output.capacityBytes <= envelopeLimit)
                remaining -= count
            }
            check(output.size() == plainLimit + 60)
            check(output.capacityBytes == envelopeLimit)
        } finally {
            output.wipe()
            chunk.fill(0)
        }
    }

    @Test
    fun boundedBufferRejectsOverflowWithoutChangingItsExistingBytes() {
        val output = BoundedArchiveBuffer(33)
        val selfCopy = BoundedArchiveBuffer(65)
        try {
            val marker = ByteArray(33) { 7 }
            output.write(marker, 0, 32)
            output.write(7)
            check(output.size() == 33 && output.toByteArray().contentEquals(marker))
            check(runCatching { output.write(7) }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { output.write(marker, 0, 1) }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { output.write(marker, -1, 1) }.exceptionOrNull() is IndexOutOfBoundsException)
            check(output.toByteArray().contentEquals(marker))
            output.reset()
            check(output.size() == 0)
            selfCopy.write(ByteArray(24) { 9 })
            selfCopy.writeTo(selfCopy)
            check(selfCopy.size() == 48 && selfCopy.toByteArray().all { it == 9.toByte() })
        } finally {
            output.wipe()
            selfCopy.wipe()
        }
    }
}
