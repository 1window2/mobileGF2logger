package dev.gf2log.app.standby

import dev.gf2log.app.settings.GameServerRegion
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One game server that standby mode can record: a client package logged in to one server region,
 * and every saved Platoon on it. Its day ends at 05:00 in [zone], the reset zone most of its
 * Platoons use; a per-Platoon override can add others to [zones], and it is then recorded before
 * whichever of them resets first.
 */
internal data class StandbyServer(
    val packageName: String,
    val region: GameServerRegion,
    val zone: ZoneId,
    val storageIds: Set<String>,
    val platoonNames: List<String>,
    val zones: Set<ZoneId> = setOf(zone),
) {
    /**
     * Names the login (client and server region) only, so a stored choice survives a reset-zone
     * override and is restored across launches.
     */
    val key: String get() = "$packageName|${region.storedValue}"
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
     * A handled reset is stored as its alarm fires, at most a few [LEAD]s before it. One further
     * ahead than this was written under a clock set forward, and is not trusted once it is back.
     */
    val MAX_HANDLED_LEAD: Duration = Duration.ofHours(1)

    /**
     * Groups saved Platoons by the server they log in to. Platoons of one client and region share
     * a login, so they are recorded together, even when their reset zones differ.
     */
    fun servers(profiles: List<StandbyProfile>): List<StandbyServer> = profiles
        .groupBy { it.packageName to it.region }
        .map { (key, members) ->
            val zones = members.map(StandbyProfile::zone)
            StandbyServer(
                packageName = key.first,
                region = key.second,
                // The most common zone; a tie goes to the first Platoon's.
                zone = zones.groupingBy { it }.eachCount().maxBy { it.value }.key,
                storageIds = members.mapTo(linkedSetOf(), StandbyProfile::storageId),
                platoonNames = members.map(StandbyProfile::platoonName).distinct(),
                zones = zones.toCollection(linkedSetOf()),
            )
        }
        .sortedWith(compareBy<StandbyServer> { it.packageName }.thenBy { it.region.ordinal })

    /** A stored choice as [StandbyServer.key] spells it; keys stored before 2.7.2 also named a zone. */
    fun selectionKey(stored: String): String = stored.split('|').take(2).joinToString("|")

    /**
     * The servers among [servers] that [keys] chose, one per client. A key whose server no longer
     * has a saved Platoon matches nothing.
     */
    fun selected(servers: List<StandbyServer>, keys: Collection<String>): List<StandbyServer> {
        val wanted = keys.mapTo(HashSet(), ::selectionKey)
        return servers.filter { it.key in wanted }.distinctBy(StandbyServer::packageName)
    }

    /**
     * [keys] after the Platoons they were chosen for ([platoons], storage IDs) moved: a chosen
     * server that no longer exists is replaced by the one server of the same client those Platoons
     * are on now, unless that client already has a chosen server that exists. Anything else stays,
     * so a choice whose Platoons are all gone keeps matching nothing.
     */
    fun followPlatoons(servers: List<StandbyServer>, keys: Set<String>, platoons: Set<String>): Set<String> {
        val existing = servers.mapTo(HashSet(), StandbyServer::key)
        val result = keys.mapTo(linkedSetOf(), ::selectionKey)
        result.filter { it !in existing }.forEach { stale ->
            val packageName = stale.substringBefore('|')
            if (result.any { it in existing && it.substringBefore('|') == packageName }) return@forEach
            val target = servers
                .filter { it.packageName == packageName && it.storageIds.any(platoons::contains) }
                .singleOrNull() ?: return@forEach
            result.remove(stale)
            result.add(target.key)
        }
        return result
    }

    /**
     * A client logs in to one server at a time and standby cannot switch servers, so at most one
     * server per client package can be chosen.
     */
    fun isValidSelection(selected: Collection<StandbyServer>): Boolean =
        selected.isNotEmpty() && selected.groupBy(StandbyServer::packageName).values.all { it.size == 1 }

    /** The earliest next reset among [server]'s zones, so no Platoon on it has its day end unrecorded. */
    fun nextReset(server: StandbyServer, now: Instant): Instant = server.zones.minOf { nextReset(it, now) }

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
        resetOf: (StandbyServer) -> Instant = { nextReset(it, now) },
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

    /**
     * The earliest group of [runs] sharing one reset: what a single scheduled wake-up records, in
     * order. Null when nothing is planned.
     */
    fun firstGroup(runs: List<StandbyRun>): List<StandbyRun> {
        val reset = runs.minOfOrNull(StandbyRun::reset) ?: return emptyList()
        return runs.filter { it.reset == reset }.sortedBy(StandbyRun::startAt)
    }

    /** [handledReset], or null when it lies [MAX_HANDLED_LEAD] or more after [now]. */
    fun trustedHandledReset(now: Instant, handledReset: Instant?): Instant? =
        handledReset?.takeIf { it.isBefore(now.plus(MAX_HANDLED_LEAD)) }

    /**
     * Where scheduling the next wake-up starts from: never before [handledReset], so a reset that
     * was already handled (even a run that ended before it) is not scheduled again. A handled
     * reset implausibly far ahead suppresses nothing, so real nights follow a corrected clock.
     */
    fun scheduleFrom(now: Instant, handledReset: Instant?): Instant {
        val handled = trustedHandledReset(now, handledReset)
        return if (handled != null && !handled.isBefore(now)) handled.plusSeconds(1) else now
    }
}
