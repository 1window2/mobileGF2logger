package dev.gf2log.app.standby

import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
    fun `a scheduled wake-up records only the earliest reset group`() {
        val now = Instant.parse("2026-10-19T14:00:00Z") // 23:00 in Seoul

        val group = StandbyPlanner.firstGroup(StandbyPlanner.plan(listOf(korea, darkwinterGlobal), now))

        // Korea resets at 20:00Z, Darkwinter Global (UTC-4) the next morning at 09:00Z.
        assertEquals(listOf(korea), group.map(StandbyRun::server))
        assertEquals(Instant.parse("2026-10-19T19:55:00Z"), group.single().startAt)
    }

    @Test
    fun `a handled reset is never scheduled again, even when its run ended early`() {
        val now = Instant.parse("2026-10-19T19:56:00Z") // 04:56 in Seoul, run already done
        val handled = Instant.parse("2026-10-19T20:00:00Z")

        val from = StandbyPlanner.scheduleFrom(now, handled)
        val next = StandbyPlanner.firstGroup(StandbyPlanner.plan(listOf(korea), from)).single()

        assertEquals(Instant.parse("2026-10-20T19:55:00Z"), next.startAt)
        assertEquals(now, StandbyPlanner.scheduleFrom(now, Instant.parse("2026-10-18T20:00:00Z")))
        assertEquals(now, StandbyPlanner.scheduleFrom(now, null))
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

    @Test
    fun `platoons of one login with different reset zones are one server`() {
        val servers = StandbyPlanner.servers(
            listOf(
                profile("a", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA, "Alpha"),
                profile("b", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA, "Bravo", ZoneOffset.UTC),
            ),
        )

        val server = servers.single()
        assertEquals(setOf("a", "b"), server.storageIds)
        assertEquals(setOf<ZoneId>(ZoneOffset.ofHours(9), ZoneOffset.UTC), server.zones)
        assertEquals("${SupportedGamePackages.HAOPLAY}|haoplay_korea", server.key)
        assertTrue(StandbyPlanner.isValidSelection(servers))
    }

    @Test
    fun `a server with several reset zones is recorded before the first of them`() {
        val server = StandbyPlanner.servers(
            listOf(
                profile("a", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA, "Alpha"),
                profile("b", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA, "Bravo", ZoneOffset.UTC),
            ),
        ).single()
        val evening = Instant.parse("2026-10-19T21:00:00Z") // 05:00 UTC comes before 05:00 in Seoul
        val morning = Instant.parse("2026-10-20T06:00:00Z") // and 05:00 in Seoul before the next 05:00 UTC

        assertEquals(Instant.parse("2026-10-20T05:00:00Z"), StandbyPlanner.plan(listOf(server), evening).single().reset)
        assertEquals(Instant.parse("2026-10-20T20:00:00Z"), StandbyPlanner.plan(listOf(server), morning).single().reset)
    }

    @Test
    fun `a choice stored before a reset-zone override still matches after it`() {
        // 2.7.1 stored the zone in the key; an override to UTC then matched nothing.
        val legacy = "${SupportedGamePackages.HAOPLAY}|haoplay_korea|+09:00"
        val overridden = StandbyPlanner.servers(
            listOf(profile("a", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_KOREA, "Alpha", ZoneOffset.UTC)),
        )

        assertEquals("${SupportedGamePackages.HAOPLAY}|haoplay_korea", StandbyPlanner.selectionKey(legacy))
        assertEquals(overridden, StandbyPlanner.selected(overridden, setOf(legacy)))
        assertEquals(overridden, StandbyPlanner.selected(overridden, setOf(overridden.single().key)))
    }

    @Test
    fun `a chosen server without a saved platoon is dropped, and none left means nothing runs`() {
        val servers = StandbyPlanner.servers(
            listOf(profile("g", SupportedGamePackages.DARKWINTER, GameServerRegion.DARKWINTER_GLOBAL, "Golf")),
        )
        val deletedKorea = korea.key

        assertEquals(servers, StandbyPlanner.selected(servers, setOf(deletedKorea, servers.single().key)))
        assertTrue(StandbyPlanner.selected(servers, setOf(deletedKorea)).isEmpty())
        assertTrue(StandbyPlanner.selected(emptyList(), setOf(deletedKorea)).isEmpty())
    }

    @Test
    fun `a choice follows its platoon to the server region it moved to`() {
        val moved = StandbyPlanner.servers(
            listOf(profile("a", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_JAPAN, "Alpha")),
        )

        assertEquals(setOf(moved.single().key), StandbyPlanner.followPlatoons(moved, setOf(korea.key), setOf("a")))
        // The Platoon is gone: the choice stays, matching nothing.
        assertEquals(setOf(korea.key), StandbyPlanner.followPlatoons(moved, setOf(korea.key), setOf("deleted")))
        // A legacy key is normalised whether or not it moves.
        assertEquals(
            setOf(moved.single().key),
            StandbyPlanner.followPlatoons(moved, setOf("${moved.single().key}|+09:00"), emptySet()),
        )
    }

    @Test
    fun `a choice does not follow when its client already has an existing chosen server`() {
        val servers = StandbyPlanner.servers(
            listOf(
                profile("a", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_JAPAN, "Alpha"),
                profile("b", SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_ASIA, "Bravo"),
            ),
        )
        val asiaKey = servers.single { it.region == GameServerRegion.HAOPLAY_ASIA }.key

        assertEquals(
            setOf(korea.key, asiaKey),
            StandbyPlanner.followPlatoons(servers, setOf(korea.key, asiaKey), setOf("a", "b")),
        )
    }

    @Test
    fun `a handled reset far in the future from a forward clock jump is ignored`() {
        val now = Instant.parse("2026-10-19T13:05:00Z") // 22:05 in Seoul, clock corrected back
        val future = Instant.parse("2026-10-19T20:00:00Z") // handled while the clock was ahead

        assertNull(StandbyPlanner.trustedHandledReset(now, future))
        assertEquals(now, StandbyPlanner.scheduleFrom(now, future))
        val next = StandbyPlanner.firstGroup(StandbyPlanner.plan(listOf(korea), StandbyPlanner.scheduleFrom(now, future)))
        assertEquals(future, next.single().reset)
    }

    @Test
    fun `a handled reset just ahead still guards against a second delivery`() {
        val now = Instant.parse("2026-10-19T19:58:00Z") // 04:58 in Seoul
        val handled = Instant.parse("2026-10-19T20:00:00Z")

        assertEquals(handled, StandbyPlanner.trustedHandledReset(now, handled))
        assertEquals(handled.plusSeconds(1), StandbyPlanner.scheduleFrom(now, handled))
    }

    private fun server(packageName: String, region: GameServerRegion) = StandbyServer(
        packageName = packageName,
        region = region,
        zone = requireNotNull(region.serverZone),
        storageIds = setOf(region.storedValue),
        platoonNames = listOf(region.storedValue),
    )

    private fun profile(
        id: String,
        packageName: String,
        region: GameServerRegion,
        name: String,
        zone: ZoneId = requireNotNull(region.serverZone),
    ) = StandbyProfile(
        storageId = id,
        packageName = packageName,
        region = region,
        zone = zone,
        platoonName = name,
    )
}
