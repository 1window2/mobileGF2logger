package dev.gf2log.app.standby

import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyPlannerTest {
    private val korea = server(SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA)
    private val japan = server(SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_JAPAN)
    private val asia = server(SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_ASIA)
    private val darkwinterGlobal = server(SupportedGamePackages.DARKWINTER, GameServerRegion.DARKWINTER_GLOBAL)
    private val darkwinterChina = server(SupportedGamePackages.DARKWINTER, GameServerRegion.DARKWINTER_CHINA)

    @Test
    fun `platoons of one client and region are grouped into one server`() {
        val servers = StandbyPlanner.servers(
            listOf(
                profile("a", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA, "Alpha"),
                profile("b", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA, "Bravo"),
                profile("c", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_JAPAN, "Charlie"),
            ),
        )

        assertEquals(2, servers.size)
        val koreaServer = servers.single { it.region == GameServerRegion.HAOPLAY_KOREA }
        assertEquals(setOf("a", "b"), koreaServer.storageIds)
        assertEquals(listOf("Alpha", "Bravo"), koreaServer.platoonNames)
    }

    @Test
    fun `selection allows at most one server per client`() {
        assertTrue(StandbyPlanner.isValidSelection(listOf(korea)))
        assertTrue(StandbyPlanner.isValidSelection(listOf(korea, darkwinterGlobal)))
        assertFalse(StandbyPlanner.isValidSelection(listOf(korea, japan)))
        assertFalse(StandbyPlanner.isValidSelection(emptyList()))
    }

    @Test
    fun `a single server starts five minutes before its 0500 reset`() {
        val now = Instant.parse("2026-10-19T14:00:00Z") // 23:00 in Seoul

        val run = StandbyPlanner.plan(listOf(korea), now).single()

        assertEquals(Instant.parse("2026-10-19T20:00:00Z"), run.reset)
        assertEquals(Instant.parse("2026-10-19T19:55:00Z"), run.startAt)
        assertEquals(Instant.parse("2026-10-19T20:05:00Z"), run.deadline)
    }

    @Test
    fun `a rehearsal gives every server the same near reset`() {
        val now = Instant.parse("2026-10-19T05:00:00Z")

        val runs = StandbyPlanner.rehearsal(listOf(korea, darkwinterGlobal), now, Duration.ofMinutes(11))

        assertEquals(listOf(darkwinterGlobal, korea).map(StandbyServer::packageName).sorted(), runs.map { it.server.packageName })
        assertEquals(Instant.parse("2026-10-19T05:01:00Z"), runs[0].startAt)
        assertEquals(Instant.parse("2026-10-19T05:06:00Z"), runs[1].startAt)
        assertEquals(Instant.parse("2026-10-19T05:16:00Z"), StandbyPlanner.finalDeadline(runs))
    }

    @Test
    fun `entering standby inside the lead window starts at once`() {
        val now = Instant.parse("2026-10-19T19:57:00Z") // 04:57 in Seoul

        val run = StandbyPlanner.plan(listOf(korea), now).single()

        assertEquals(Instant.parse("2026-10-19T20:00:00Z"), run.reset)
        assertEquals(now, run.startAt)
    }

    @Test
    fun `a reset less than a minute away rolls over to the next day`() {
        val now = Instant.parse("2026-10-19T19:59:30Z")

        val run = StandbyPlanner.plan(listOf(korea), now).single()

        assertEquals(Instant.parse("2026-10-20T20:00:00Z"), run.reset)
        assertEquals(Instant.parse("2026-10-20T19:55:00Z"), run.startAt)
    }

    @Test
    fun `servers with different resets run in reset order`() {
        val now = Instant.parse("2026-10-19T14:00:00Z")

        val runs = StandbyPlanner.plan(listOf(darkwinterGlobal, korea), now)

        assertEquals(listOf(korea, darkwinterGlobal), runs.map(StandbyRun::server))
        assertEquals(Instant.parse("2026-10-19T20:00:00Z"), runs[0].reset)
        assertEquals(Instant.parse("2026-10-20T09:00:00Z"), runs[1].reset)
        assertEquals(Instant.parse("2026-10-20T09:05:00Z"), StandbyPlanner.finalDeadline(runs))
    }

    @Test
    fun `servers sharing a reset are staggered so both start before it`() {
        val now = Instant.parse("2026-10-19T14:00:00Z")

        val runs = StandbyPlanner.plan(listOf(darkwinterChina, asia), now)

        assertEquals(2, runs.size)
        assertEquals(runs[0].reset, runs[1].reset)
        assertEquals(Instant.parse("2026-10-19T21:00:00Z"), runs[0].reset)
        assertEquals(Instant.parse("2026-10-19T20:50:00Z"), runs[0].startAt)
        assertEquals(Instant.parse("2026-10-19T20:55:00Z"), runs[1].startAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `planning rejects two servers of one client`() {
        StandbyPlanner.plan(listOf(korea, japan), Instant.parse("2026-10-19T14:00:00Z"))
    }

    @Test
    fun `nothing selected has no deadline`() {
        assertNull(StandbyPlanner.finalDeadline(emptyList()))
    }

    private fun server(packageName: String, region: GameServerRegion) = StandbyServer(
        packageName = packageName,
        region = region,
        zone = requireNotNull(region.serverZone),
        storageIds = setOf(region.storedValue),
        platoonNames = listOf(region.storedValue),
    )

    private fun profile(id: String, packageName: String, region: GameServerRegion, name: String) = StandbyProfile(
        storageId = id,
        packageName = packageName,
        region = region,
        zone = requireNotNull(region.serverZone) as ZoneId,
        platoonName = name,
    )
}
