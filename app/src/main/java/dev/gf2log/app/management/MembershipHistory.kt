package dev.gf2log.app.management

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * A membership boundary whose calendar date is always known and whose clock
 * time may be unknown.
 */
data class MembershipBoundaryValue(
    val date: LocalDate,
    val instant: Instant,
    val timeKnown: Boolean,
)

/** How far a manual boundary may run ahead of the device clock, as for a roster CSV import. */
internal val MAX_MANUAL_BOUNDARY_CLOCK_SKEW: Duration = Duration.ofHours(1)

/** The latest calendar date in [zone] that a manual membership boundary may carry at [now]. */
fun latestMembershipBoundaryDate(zone: ZoneId, now: Instant = Instant.now()): LocalDate =
    now.plus(MAX_MANUAL_BOUNDARY_CLOCK_SKEW).atZone(zone).toLocalDate()

/**
 * Whether a manual join or withdrawal has already happened at [now]: its calendar date is not
 * after [latestMembershipBoundaryDate], and a known time is not after [now] beyond the clock
 * skew. A future withdrawal would mark a current member withdrawn at once and overlap the next
 * roster's period, and any future boundary would add phantom weeks.
 */
fun isMembershipBoundaryWithinPresent(
    boundary: MembershipBoundaryValue,
    zone: ZoneId,
    now: Instant = Instant.now(),
): Boolean = !boundary.date.isAfter(latestMembershipBoundaryDate(zone, now)) &&
    (!boundary.timeKnown || !boundary.instant.isAfter(now.plus(MAX_MANUAL_BOUNDARY_CLOCK_SKEW)))

fun isValidMembershipRange(
    joined: MembershipBoundaryValue,
    withdrew: MembershipBoundaryValue?,
): Boolean = when {
    withdrew == null -> true
    withdrew.date.isAfter(joined.date) -> true
    withdrew.date.isBefore(joined.date) -> false
    !joined.timeKnown || !withdrew.timeKnown -> true
    else -> !withdrew.instant.isBefore(joined.instant)
}

/**
 * Only 21960 Updates boundaries are immutable. An [EvidenceSource.ACTIVITY] boundary is an
 * inference from a name-matched activity, so it stays editable like a roster observation.
 */
fun EvidenceSource.isImmutableMembershipBoundary(): Boolean =
    this == EvidenceSource.GAME_UPDATES

/**
 * Sources whose boundary events carry the instant the change happened, so the weekly roster
 * applies them at that instant: exact Updates and activity-dated roster boundaries.
 */
internal val TIMED_BOUNDARY_SOURCES = setOf(EvidenceSource.GAME_UPDATES, EvidenceSource.ACTIVITY)

/**
 * Only a recorded packet-linked boundary is immutable. A period opened by an exact Updates
 * withdrawal has a GAME_UPDATES join source but no join time, and that unknown side stays
 * editable.
 */
internal fun isImmutableMembershipBoundary(
    value: MembershipBoundaryValue?,
    source: EvidenceSource?,
): Boolean = value != null && source?.isImmutableMembershipBoundary() == true

/** Whether the join side may be edited; see [isImmutableMembershipBoundary]. */
val MembershipPeriod.isJoinedBoundaryEditable: Boolean
    get() = joinedAt == null || !joinedSource.isImmutableMembershipBoundary()

/** Whether the withdrawal side may be edited; see [isImmutableMembershipBoundary]. */
val MembershipPeriod.isLeftBoundaryEditable: Boolean
    get() = leftAt == null || leftSource?.isImmutableMembershipBoundary() != true

internal fun editableMembershipBoundaryChanged(
    original: MembershipBoundaryValue?,
    requested: MembershipBoundaryValue?,
    source: EvidenceSource?,
): Boolean = !isImmutableMembershipBoundary(original, source) && original != requested

internal fun migrationCalendarDate(epochMillis: Long, zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()

internal object MembershipChronology {
    fun withdrawalPredatesRosterPresence(
        withdrewAt: Instant,
        latestRosterPresenceAt: Instant?,
    ): Boolean = latestRosterPresenceAt?.isAfter(withdrewAt) == true

    fun hasSupersedingOppositeBoundary(
        boundaryAt: Instant,
        observedAt: Instant,
        oppositeBoundaryTimes: Iterable<Instant>,
    ): Boolean = oppositeBoundaryTimes.any { oppositeAt ->
        oppositeAt.isAfter(boundaryAt) && !oppositeAt.isAfter(observedAt)
    }

    fun shouldRestoreRosterActiveMembershipPeriod(
        memberIsActive: Boolean,
        withdrawalPredatesRoster: Boolean,
    ): Boolean = memberIsActive && withdrawalPredatesRoster
}

/**
 * Pure presentation policy for the Join/Withdraw section.
 *
 * Keeping evidence precedence and deduplication outside the Activity prevents
 * the UI layer from deciding which persistence fact is authoritative.
 */
object MembershipEventPresentation {
    private const val DEDUPLICATION_MILLIS = 48L * 60L * 60L * 1000L
    private val eventTime = DateTimeFormatter.ofPattern("HH:mm")

    val displayedTypes = setOf(
        MemberEventType.JOINED,
        MemberEventType.REJOINED,
        MemberEventType.LEFT,
        MemberEventType.REMOVED,
    )

    val displayedSources = setOf(
        EvidenceSource.SNAPSHOT,
        EvidenceSource.LEGACY_IMPORT,
        EvidenceSource.GAME_UPDATES,
        EvidenceSource.MANUAL,
        EvidenceSource.ACTIVITY,
    )

    fun deduplicate(events: List<MemberEvent>): List<MemberEvent> {
        val selected = mutableListOf<MemberEvent>()
        events
            .filter(::isDisplayable)
            .sortedWith(
                compareByDescending<MemberEvent> { evidencePriority(it.source) }
                    .thenByDescending { it.id },
            )
            .forEach { candidate ->
                val duplicate = selected.any { existing ->
                    existing.uid == candidate.uid &&
                        boundary(existing.type) == boundary(candidate.type) &&
                        abs(
                            (existing.occurredAt ?: existing.observedAt).toEpochMilli() -
                                (candidate.occurredAt ?: candidate.observedAt).toEpochMilli(),
                        ) <= DEDUPLICATION_MILLIS &&
                        !hasOppositeBoundaryBetween(events, existing, candidate) &&
                        !(existing.source == EvidenceSource.GAME_UPDATES &&
                            candidate.source == EvidenceSource.GAME_UPDATES)
                }
                if (!duplicate) selected += candidate
            }
        return selected.sortedWith(
            compareBy<MemberEvent>(
                { it.occurredAt == null },
                { it.occurredAt ?: it.observedAt },
                { it.id },
            ),
        )
    }

    fun timePrefix(event: MemberEvent, zoneId: ZoneId): String? = when {
        event.source in TIMED_BOUNDARY_SOURCES && event.occurredAt != null ->
            eventTime.format(event.occurredAt.atZone(zoneId))
        event.source == EvidenceSource.MANUAL && !event.timeKnown -> "??:??"
        event.source == EvidenceSource.MANUAL && event.occurredAt != null ->
            eventTime.format(event.occurredAt.atZone(zoneId))
        else -> null
    }

    fun calendarDate(event: MemberEvent, zoneId: ZoneId): LocalDate? =
        event.eventDate ?: event.occurredAt?.atZone(zoneId)?.toLocalDate()

    private fun isDisplayable(event: MemberEvent): Boolean =
        event.type in displayedTypes && event.source in displayedSources

    private fun evidencePriority(source: EvidenceSource): Int = when (source) {
        EvidenceSource.GAME_UPDATES -> 4
        EvidenceSource.MANUAL -> 3
        EvidenceSource.ACTIVITY -> 2
        EvidenceSource.SNAPSHOT, EvidenceSource.LEGACY_IMPORT -> 1
    }

    private fun boundary(type: MemberEventType): Int = when (type) {
        MemberEventType.JOINED, MemberEventType.REJOINED -> 1
        MemberEventType.LEFT, MemberEventType.REMOVED -> 2
        MemberEventType.RENAMED -> 0
    }

    private fun hasOppositeBoundaryBetween(
        events: List<MemberEvent>,
        first: MemberEvent,
        second: MemberEvent,
    ): Boolean {
        val firstTime = first.occurredAt ?: first.observedAt
        val secondTime = second.occurredAt ?: second.observedAt
        val start = minOf(firstTime, secondTime)
        val end = maxOf(firstTime, secondTime)
        val oppositeBoundary = if (boundary(first.type) == 1) 2 else 1
        return events.any { event ->
            event.uid == first.uid &&
                boundary(event.type) == oppositeBoundary &&
                (event.occurredAt ?: event.observedAt).let { it.isAfter(start) && it.isBefore(end) }
        }
    }
}
