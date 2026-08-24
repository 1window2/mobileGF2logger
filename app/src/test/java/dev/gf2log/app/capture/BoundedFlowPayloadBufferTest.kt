package dev.gf2log.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFlowPayloadBufferTest {
    @Test
    fun overflowDiscardsAndRejectsUntilFlowRemoval() {
        val buffer = BoundedFlowPayloadBuffer<String>(2)
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(7, "a"))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(7, "b"))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.OVERFLOW, buffer.offer(7, "c"))
        assertTrue(buffer.take(7).isEmpty())
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.REJECTED, buffer.offer(7, "d"))

        buffer.remove(7)
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(7, "e"))
        assertEquals(listOf("e"), buffer.take(7))
    }

    @Test
    fun identityTakesOneFlowWithoutTouchingAnother() {
        val buffer = BoundedFlowPayloadBuffer<String>(2)
        buffer.offer(1, "one")
        buffer.offer(2, "two")

        assertEquals(listOf("one"), buffer.take(1))
        assertEquals(listOf("two"), buffer.take(2))
    }
}
