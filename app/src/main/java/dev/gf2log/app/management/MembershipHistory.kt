package dev.gf2log.app.management

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

fun EvidenceSource.isImmutableMembershipBoundary(): Boolean =
    this == EvidenceSource.GAME_UPDATES

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
        event.source == EvidenceSource.GAME_UPDATES && event.occurredAt != null ->
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
        EvidenceSource.GAME_UPDATES -> 3
        EvidenceSource.MANUAL -> 2
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
