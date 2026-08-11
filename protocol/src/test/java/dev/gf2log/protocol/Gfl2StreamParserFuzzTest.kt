package dev.gf2log.protocol

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class Gfl2StreamParserFuzzTest {
    @Test
    fun deterministicMalformedStreamsStayBoundedAndParserRemainsReusable() {
        val random = Random(0x6f_32)

        repeat(256) { caseIndex ->
            val parser = Gfl2StreamParser(
                maximumBufferedBytes = 4_096,
                maximumPendingPayloadBytes = 4_096,
                maximumPendingContinuations = 8,
            )
            var eventCount = 0
            repeat(24) {
                val bytes = random.nextBytes(random.nextInt(0, 768))
                eventCount += parser.accept(bytes).size
            }
            eventCount += parser.finish().size

            assertTrue("case $caseIndex emitted an implausible event count", eventCount <= 24 * 768)
            parser.reset()
            assertTrue(parser.accept(byteArrayOf()).isEmpty())
            assertTrue(parser.finish().isEmpty())
        }
    }
}
