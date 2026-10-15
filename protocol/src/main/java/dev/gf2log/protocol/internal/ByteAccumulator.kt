package dev.gf2log.protocol.internal

import dev.gf2log.protocol.ProtocolException

internal class ByteAccumulator(
    private val maximumSize: Int,
) {
    private var bytes = ByteArray(256)
    private var readPosition = 0
    private var writePosition = 0

    val available: Int
        get() = writePosition - readPosition

    /** Backing array holding [available] unread bytes from [readOffset]; valid until mutated. */
    val storage: ByteArray
        get() = bytes

    val readOffset: Int
        get() = readPosition

    fun append(source: ByteArray) {
        if (source.isEmpty()) return
        if (source.size > maximumSize - available) {
            throw ProtocolException("Buffered stream data exceeds $maximumSize bytes")
        }

        ensureCapacity(source.size)
        source.copyInto(bytes, writePosition)
        writePosition += source.size
    }

    fun peekUInt16Le(offset: Int): Int {
        requireAvailable(offset, 2)
        val position = readPosition + offset
        return (bytes[position].toInt() and 0xFF) or
            ((bytes[position + 1].toInt() and 0xFF) shl 8)
    }

    fun peekUInt24Le(offset: Int): Int {
        requireAvailable(offset, 3)
        val position = readPosition + offset
        return (bytes[position].toInt() and 0xFF) or
            ((bytes[position + 1].toInt() and 0xFF) shl 8) or
            ((bytes[position + 2].toInt() and 0xFF) shl 16)
    }

    fun discard(count: Int) {
        requireAvailable(0, count)
        readPosition += count
        if (readPosition == writePosition) clear()
    }

    fun clear() {
        readPosition = 0
        writePosition = 0
    }

    /** Compacts or grows only when [extra] bytes do not fit after the current write position. */
    private fun ensureCapacity(extra: Int) {
        if (writePosition + extra <= bytes.size) return
        val required = available + extra
        if (required <= bytes.size) {
            compact()
            return
        }

        var newSize = bytes.size
        while (newSize < required) newSize = minOf(maximumSize, newSize * 2)
        val replacement = ByteArray(newSize)
        bytes.copyInto(replacement, 0, readPosition, writePosition)
        writePosition = available
        readPosition = 0
        bytes = replacement
    }

    private fun compact() {
        val length = available
        bytes.copyInto(bytes, 0, readPosition, writePosition)
        readPosition = 0
        writePosition = length
    }

    private fun requireAvailable(offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset + count <= available) {
            "Requested $count bytes at $offset with only $available bytes available"
        }
    }
}
