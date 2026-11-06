package dev.gf2log.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundWorkDeliveryTest {
    @Test
    fun `a result for a destroyed or superseded screen still runs its completion effects once`() {
        var always = 0
        var onResult = 0

        deliverResult(Result.success(Unit), current = false, always = { always += 1 }, onResult = { onResult += 1 })

        assertEquals(1, always)
        assertEquals(0, onResult)
    }

    @Test
    fun `a result for the live screen runs both, completion effects first`() {
        val order = mutableListOf<String>()

        deliverResult(
            Result.failure<Int>(IllegalStateException()),
            current = true,
            always = { order += "always" },
            onResult = { order += "onResult" },
        )

        assertEquals(listOf("always", "onResult"), order)
    }
}
