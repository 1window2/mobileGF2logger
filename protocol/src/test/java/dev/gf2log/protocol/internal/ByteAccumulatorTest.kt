package dev.gf2log.protocol.internal

import dev.gf2log.protocol.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteAccumulatorTest {
    @Test
    fun compactsAndGrowsWithoutLosingUnreadBytes() {
        val buffer = ByteAccumulator(maximumSize = 1_024)
        buffer.append(ByteArray(200) { it.toByte() })
        buffer.discard(150)
        // 50 unread + 100 new fits the initial 256-byte array only after compaction.
        buffer.append(ByteArray(100) { (200 + it).toByte() })
        assertEquals(150, buffer.available)
        assertEquals(150 or (151 shl 8), buffer.peekUInt16Le(0))

        buffer.append(ByteArray(600) { 7 })
        assertEquals(750, buffer.available)
        assertEquals(150, buffer.storage[buffer.readOffset].toInt() and 0xFF)
        assertEquals(7, buffer.storage[buffer.readOffset + 749].toInt())

        buffer.discard(750)
        assertEquals(0, buffer.available)
        assertEquals(0, buffer.readOffset)
    }

    @Test
    fun rejectsInputBeyondTheBound() {
        val buffer = ByteAccumulator(maximumSize = 64)
        buffer.append(ByteArray(60))
        assertTrue(runCatching { buffer.append(ByteArray(5)) }.exceptionOrNull() is ProtocolException)
        assertEquals(60, buffer.available)
    }
}
