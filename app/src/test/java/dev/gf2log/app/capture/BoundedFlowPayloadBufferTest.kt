package dev.gf2log.app.capture

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFlowPayloadBufferTest {
    @Test
    fun overflowDiscardsAndRejectsUntilFlowRemoval() {
        val buffer = BoundedFlowPayloadBuffer<String>(2, 4)
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(7, "a"))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(7, "b"))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.OVERFLOW, buffer.offer(7, "c"))
        assertTrue(buffer.isRejected(7))
        assertTrue(buffer.take(7).isEmpty())
        assertTrue(buffer.isRejected(7))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.REJECTED, buffer.offer(7, "d"))

        buffer.remove(7)
        assertTrue(!buffer.isRejected(7))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(7, "e"))
        assertEquals(listOf("e"), buffer.take(7))
    }

    @Test
    fun identityTakesOneFlowWithoutTouchingAnother() {
        val buffer = BoundedFlowPayloadBuffer<String>(2, 4)
        buffer.offer(1, "one")
        buffer.offer(2, "two")

        assertEquals(listOf("one"), buffer.take(1))
        assertEquals(listOf("two"), buffer.take(2))
    }

    @Test
    fun aggregateOverflowRejectsOnlyTheFlowThatExceededTheGlobalBudget() {
        val buffer = BoundedFlowPayloadBuffer<String>(4, 3)
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(1, "one-a"))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(1, "one-b"))
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(2, "two"))

        assertEquals(BoundedFlowPayloadBuffer.OfferResult.OVERFLOW, buffer.offer(1, "one-c"))
        assertTrue(buffer.isRejected(1))
        assertTrue(buffer.take(1).isEmpty())
        assertEquals(listOf("two"), buffer.take(2))

        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(3, "three"))
        assertEquals(listOf("three"), buffer.take(3))
    }

    @Test
    fun removalAndClearReleaseAggregateCapacity() {
        val buffer = BoundedFlowPayloadBuffer<String>(2, 2)
        buffer.offer(1, "one")
        buffer.offer(2, "two")
        buffer.remove(1)
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(3, "three"))

        buffer.clear()
        assertEquals(BoundedFlowPayloadBuffer.OfferResult.ACCEPTED, buffer.offer(4, "four"))
        assertEquals(listOf("four"), buffer.take(4))
    }

    @Test
    fun everyStatefulOperationIsSerializedAcrossParserAndCloseThreads() {
        val synchronizedMethods = setOf("offer", "take", "isRejected", "reject", "remove", "clear")
        val methodsByName = BoundedFlowPayloadBuffer::class.java.declaredMethods
            .filter { it.name in synchronizedMethods }
            .associateBy { it.name }

        assertEquals(synchronizedMethods, methodsByName.keys)
        synchronizedMethods.forEach { name ->
            assertTrue("$name must synchronize quarantine bookkeeping", Modifier.isSynchronized(
                requireNotNull(methodsByName[name]).modifiers,
            ))
        }
    }
}
