package dev.gf2log.app.standby

import dev.gf2log.app.settings.GameServerRegion
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One game server that standby mode can record: a client package logged in to one server region,
 * and every saved Platoon on it. Its day ends at 05:00 in [zone].
 */
internal data class StandbyServer(
    val packageName: String,
    val region: GameServerRegion,
    val zone: ZoneId,
    val storageIds: Set<String>,
    val platoonNames: List<String>,
) {
    /** Stable across launches, so the last checkbox choice can be restored. */
    val key: String get() = "$packageName|${region.storedValue}|${zone.id}"
}

/** A saved Platoon as standby planning sees it. */
internal data class StandbyProfile(
    val storageId: String,
    val packageName: String,
    val region: GameServerRegion,
    val zone: ZoneId,
    val platoonName: String,
)

/** One unattended recording: start before [reset], finish by [deadline]. */
internal data class StandbyRun(
    val server: StandbyServer,
    val reset: Instant,
    val startAt: Instant,
) {
    val deadline: Instant get() = reset.plus(StandbyPlanner.GRACE)
}

/** Pure planning rules for standby mode's automatic recordings; no Android dependencies. */
internal object StandbyPlanner {
    /** How long before a server's day ends its recording starts. */
    val LEAD: Duration = Duration.ofMinutes(5)

    /** How long after the reset a late recording may still finish before it is abandoned. */
    val GRACE: Duration = Duration.ofMinutes(5)

    /** A reset closer than this is skipped in favour of the next day's, so a run is never rushed. */
    val MIN_NOTICE: Duration = Duration.ofMinutes(1)

    private val RESET_HOUR = GameServerRegion.RESET_TIME

    /**
     * Groups saved Platoons by the server they log in to. Platoons of one client and region share
     * a login, so they are recorded together.
     */
    fun servers(profiles: List<StandbyProfile>): List<StandbyServer> = profiles
        .groupBy { Triple(it.packageName, it.region, it.zone) }
        .map { (key, members) ->
            StandbyServer(
                packageName = key.first,
                region = key.second,
                zone = key.third,
                storageIds = members.mapTo(linkedSetOf(), StandbyProfile::storageId),
                platoonNames = members.map(StandbyProfile::platoonName).distinct(),
            )
        }
        .sortedWith(compareBy<StandbyServer> { it.packageName }.thenBy { it.region.ordinal }.thenBy { it.zone.id })

    /**
     * A client logs in to one server at a time and standby cannot switch servers, so at most one
     * server per client package can be chosen.
     */
    fun isValidSelection(selected: Collection<StandbyServer>): Boolean =
        selected.isNotEmpty() && selected.groupBy(StandbyServer::packageName).values.all { it.size == 1 }

    /** The next 05:00 in [zone] that is at least [MIN_NOTICE] after [now]. */
    fun nextReset(zone: ZoneId, now: Instant): Instant {
        val local = now.atZone(zone)
        var reset = ZonedDateTime.of(local.toLocalDate(), RESET_HOUR, zone)
        while (!reset.toInstant().isAfter(now.plus(MIN_NOTICE))) reset = reset.plusDays(1)
        return reset.toInstant()
    }

    /**
     * Orders the chosen servers by reset time. Servers sharing one reset instant run one after
     * another, so the earlier ones start [LEAD] earlier per server still to follow; a start already
     * in the past begins immediately.
     */
    fun plan(
        selected: Collection<StandbyServer>,
        now: Instant,
        resetOf: (StandbyServer) -> Instant = { nextReset(it.zone, now) },
    ): List<StandbyRun> {
        require(isValidSelection(selected)) { "One server per client must be selected" }
        return selected
            .map { it to resetOf(it) }
            .groupBy({ it.second }, { it.first })
            .toSortedMap()
            .flatMap { (reset, servers) ->
                val ordered = servers.sortedBy(StandbyServer::packageName)
                ordered.mapIndexed { index, server ->
                    val lead = LEAD.multipliedBy((ordered.size - index).toLong())
                    val start = reset.minus(lead)
                    StandbyRun(server, reset, if (start.isBefore(now)) now else start)
                }
            }
    }

    /**
     * A plan in which every chosen server resets [resetIn] from [now], so the runs follow one
     * another [LEAD] apart. Debug builds use it to rehearse a night on a device.
     */
    fun rehearsal(selected: Collection<StandbyServer>, now: Instant, resetIn: Duration): List<StandbyRun> =
        plan(selected, now) { now.plus(resetIn) }

    /** Standby must end, with the phone locked, by this instant whatever happened before. */
    fun finalDeadline(runs: List<StandbyRun>): Instant? = runs.maxOfOrNull(StandbyRun::deadline)
}
