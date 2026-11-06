package dev.gf2log.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneCaptionTest {
    private val now = Instant.parse("2026-11-06T00:00:00Z")

    @Test
    fun `a game zone with another offset than the phone needs a caption`() {
        assertFalse(Labels.sameOffset(ZoneId.of("Asia/Seoul"), ZoneOffset.UTC, now))
        assertFalse(Labels.sameOffset(ZoneId.of("Asia/Seoul"), ZoneId.of("America/New_York"), now))
    }

    @Test
    fun `a game zone on the phone's offset reads like the phone clock`() {
        assertTrue(Labels.sameOffset(ZoneId.of("Asia/Seoul"), ZoneId.of("Asia/Tokyo"), now))
        assertTrue(Labels.sameOffset(ZoneId.of("UTC"), ZoneOffset.UTC, now))
    }

    @Test
    fun `daylight saving is judged at the moment shown`() {
        val summer = Instant.parse("2026-07-01T00:00:00Z")
        assertTrue(Labels.sameOffset(ZoneId.of("Europe/London"), ZoneOffset.UTC, now))
        assertFalse(Labels.sameOffset(ZoneId.of("Europe/London"), ZoneOffset.UTC, summer))
    }
}
