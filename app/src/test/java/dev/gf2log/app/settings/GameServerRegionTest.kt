package dev.gf2log.app.settings

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class GameServerRegionTest {
    @Test
    fun `known regions use the documented 0500 server reset`() {
        val beforeReset = Instant.parse("2026-08-23T19:30:00Z")

        assertEquals(
            Instant.parse("2026-08-23T20:00:00Z"),
            GameServerRegion.HAOPLAY_KOREA.nextReset(beforeReset),
        )
        assertEquals(
            Instant.parse("2026-08-23T20:00:00Z"),
            GameServerRegion.HAOPLAY_JAPAN.nextReset(beforeReset),
        )
        assertEquals(
            Instant.parse("2026-08-24T05:00:00Z"),
            GameServerRegion.HAOPLAY_GLOBAL.nextReset(beforeReset),
        )
        assertEquals(
            Instant.parse("2026-08-23T21:00:00Z"),
            GameServerRegion.HAOPLAY_ASIA.nextReset(beforeReset),
        )
        assertEquals(
            Instant.parse("2026-08-24T09:00:00Z"),
            GameServerRegion.DARKWINTER_GLOBAL.nextReset(beforeReset),
        )
        assertEquals(
            Instant.parse("2026-08-23T21:00:00Z"),
            GameServerRegion.DARKWINTER_CHINA.nextReset(beforeReset),
        )
    }

    @Test
    fun `reset advances to next day at the exact boundary`() {
        val exactReset = Instant.parse("2026-08-23T20:00:00Z")

        assertEquals(
            Instant.parse("2026-08-24T20:00:00Z"),
            GameServerRegion.HAOPLAY_KOREA.nextReset(exactReset),
        )
    }

    @Test
    fun `stored values round trip and unknown values remain manual`() {
        GameServerRegion.entries.forEach { region ->
            assertEquals(region, GameServerRegion.fromStored(region.storedValue))
        }
        assertEquals(GameServerRegion.MANUAL, GameServerRegion.fromStored("unknown"))
        assertEquals(ZoneOffset.ofHours(-4), GameServerRegion.DARKWINTER_GLOBAL.serverZone)
    }
}
