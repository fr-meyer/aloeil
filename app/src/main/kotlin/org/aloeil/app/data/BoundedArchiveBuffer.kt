package org.aloeil.app.data

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher

/** Keeps archive buffer growth inside its existing limit, including envelope overhead. */
internal class BoundedArchiveBuffer(private val maximumBytes: Int) :
    ByteArrayOutputStream(minOf(32, maximumBytes.coerceAtLeast(0))) {
    init { require(maximumBytes >= 0) }

    /** Read-only allocation metadata; never exposes buffer contents. */
    @get:Synchronized
    val capacityBytes: Int get() = buf.size

    @Synchronized
    override fun write(value: Int) {
        require(count < maximumBytes) { "Archive is too large" }
        ensureBoundedCapacity(count + 1)
        buf[count++] = value.toByte()
    }

    @Synchronized
    override fun write(source: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > source.size - length) {
            throw IndexOutOfBoundsException()
        }
        require(length <= maximumBytes - count) { "Archive is too large" }
        // ByteArrayOutputStream.writeTo(this) may supply our own backing array.
        // Growth wipes the old array, so use its replacement for that alias.
        val ownedSource = source === buf
        ensureBoundedCapacity(count + length)
        (if (ownedSource) buf else source).copyInto(buf, count, offset, offset + length)
        count += length
    }

    private fun ensureBoundedCapacity(required: Int) {
        if (required <= buf.size) return
        val capacity = maxOf(required, minOf(maximumBytes.toLong(), buf.size.toLong() * 2).toInt())
        val old = buf
        val replacement = old.copyOf(capacity)
        old.fill(0)
        buf = replacement
    }

    /** Encrypt the owned plaintext without creating an intermediate plaintext copy. */
    @Synchronized
    fun encryptInto(cipher: Cipher, destination: ByteArray, offset: Int): Int =
        cipher.doFinal(buf, 0, count, destination, offset)

    @Synchronized
    fun wipe() {
        buf.fill(0)
        count = 0
    }

    @Synchronized
    override fun reset() = wipe()
}
