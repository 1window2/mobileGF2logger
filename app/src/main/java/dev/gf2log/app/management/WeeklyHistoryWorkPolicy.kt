package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounds packet-triggered weekly-history work independently of parser behavior. */
internal object WeeklyHistoryWorkPolicy {
    const val MAX_CHANGED_WEEKS_PER_INGEST = 32

    /**
     * Every week whose live revision can change when evidence at [instants] is stored: the game
     * week of each instant, then the listed neighbours that also read it. A roster at exactly a
     * Sunday 05:00 reset is the closing Saturday capture of the previous week; an instant before
     * 05:00 on a calendar Sunday falls in the next week's calendar-day Join / Withdraw section;
     * and every later week up to the next stored roster carries the instant in as its baseline
     * roster or as a roster-changing membership event. Neighbours are limited to
     * [listedPeriodStarts], so no revision is recorded for a week the history does not list, and
     * the whole result stays within [MAX_CHANGED_WEEKS_PER_INGEST], game weeks first.
     */
    fun affectedPeriodStarts(
        instants: Sequence<Instant>,
        zoneId: ZoneId,
        listedPeriodStarts: Collection<LocalDate>,
        nextSnapshotAfter: (Instant) -> Instant?,
    ): List<LocalDate> {
        val latestByWeek = linkedMapOf<LocalDate, Instant>()
        val neighbours = sortedSetOf<LocalDate>()
        instants.forEach { instant ->
            val week = PlatoonPeriods.weekStart(PlatoonPeriods.gameDay(instant, zoneId))
            val latest = latestByWeek[week]
            if (latest == null && latestByWeek.size == MAX_CHANGED_WEEKS_PER_INGEST) return@forEach
            if (latest == null || instant.isAfter(latest)) latestByWeek[week] = instant
            if (instant == PlatoonPeriods.periodStartInstant(week, zoneId)) {
                neighbours += week.minusDays(DAYS_PER_WEEK)
            }
            neighbours += PlatoonPeriods.weekStart(instant.atZone(zoneId).toLocalDate())
        }
        val listed = listedPeriodStarts.distinct().sorted()
        latestByWeek.forEach { (week, latest) ->
            val nextSnapshot = nextSnapshotAfter(latest)
            listed.asSequence()
                .dropWhile { !it.isAfter(week) }
                .takeWhile { later ->
                    nextSnapshot == null ||
                        !nextSnapshot.isBefore(PlatoonPeriods.periodStartInstant(later, zoneId))
                }
                .forEach(neighbours::add)
        }
        return (latestByWeek.keys + neighbours.filter { it in listed })
            .distinct()
            .take(MAX_CHANGED_WEEKS_PER_INGEST)
    }

    /** What a member edit can change about the weeks that list or show that member. */
    enum class MemberEdit {
        /** Rewrites membership periods or boundaries: listed weeks and rosters can change. */
        MEMBERSHIP,

        /** Changes only the custom name or private note: weeks and rosters stay as they were. */
        DISPLAY_ONLY,

        /** Deletes every row of the member: afterwards no week shows the member. */
        REMOVAL,
    }

    /**
     * The weeks one member edit refreshes: the weeks that showed the member before it and are
     * still listed, then the weeks that show the member after it, in week order. A null
     * [showingBefore] means the pre-scan failed, so every listed week is refreshed. The
     * post-edit listing and scan are evaluated only when [edit] can change them: a
     * [MemberEdit.DISPLAY_ONLY] edit reuses the pre-scan, because its listing and rosters are
     * those it already read, and after [MemberEdit.REMOVAL] the member is shown nowhere.
     */
    fun memberEditPeriodStarts(
        edit: MemberEdit,
        showingBefore: Set<LocalDate>?,
        listedAfter: () -> List<LocalDate>,
        showingAfter: (listed: List<LocalDate>) -> Set<LocalDate>,
    ): List<LocalDate> {
        if (showingBefore == null) return listedAfter()
        if (edit == MemberEdit.DISPLAY_ONLY) return showingBefore.sorted()
        val listed = listedAfter()
        val current = listed.toSet()
        val after = if (edit == MemberEdit.REMOVAL) emptySet() else showingAfter(listed)
        return (showingBefore.filter { it in current } + after).distinct().sorted()
    }

    private const val DAYS_PER_WEEK = 7L
}
