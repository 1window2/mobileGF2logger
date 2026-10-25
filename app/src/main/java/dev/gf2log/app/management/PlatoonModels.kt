package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate

data class PlatoonSnapshot(
    val id: Long,
    val capturedAt: Instant,
    val members: List<SnapshotMember>,
    val sourceFile: String? = null,
    val gameVersion: String? = null,
)

internal fun PlatoonSnapshot.member(uid: Long): SnapshotMember? = members.firstOrNull { it.uid == uid }

data class SnapshotMember(
    val uid: Long,
    val name: String,
    val level: Long,
    val weeklyMerit: Long,
    val totalMerit: Long,
    val highScore: Long,
    val totalScore: Long,
    val lastLogin: Long,
)

data class MembershipPeriod(
    val id: Long,
    val uid: Long,
    val joinedAt: Instant?,
    val leftAt: Instant?,
    val joinedDate: LocalDate?,
    val leftDate: LocalDate?,
    val joinedTimeKnown: Boolean,
    val leftTimeKnown: Boolean?,
    val joinedPrecision: EvidencePrecision,
    val leftPrecision: EvidencePrecision?,
    val joinedSource: EvidenceSource,
    val leftSource: EvidenceSource?,
    val note: String,
)

data class MemberStatus(
    val uid: Long,
    val name: String,
    val level: Long,
    val isActive: Boolean,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val note: String,
    val membershipPeriods: List<MembershipPeriod>,
)

/**
 * The membership period a member list shows: the open period, or the most recently closed one
 * when the member has withdrawn. Unknown boundaries sort before every known one.
 */
fun MemberStatus.latestMembershipPeriod(): MembershipPeriod? {
    val open = membershipPeriods.filter { it.leftAt == null }
    return (open.ifEmpty { membershipPeriods }).maxWithOrNull(
        compareBy<MembershipPeriod, Instant?>(nullsFirst()) { it.leftAt }
            .thenBy(nullsFirst()) { it.joinedAt }
            .thenBy { it.id },
    )
}

data class MemberEvent(
    val id: Long,
    val uid: Long,
    val type: MemberEventType,
    val occurredAt: Instant?,
    val eventDate: LocalDate?,
    val timeKnown: Boolean,
    val observedAt: Instant,
    val precision: EvidencePrecision,
    val source: EvidenceSource,
    val note: String,
)

data class PlatoonActivityObservation(
    val occurredAt: Instant,
    val actionId: Long,
    val kind: Long,
    val memberName: String,
)

data class PlatoonUpdateObservation(
    val kind: Long,
    val occurredAt: Instant,
    val members: List<PlatoonUpdateMemberObservation>,
)

data class PlatoonUpdateMemberObservation(
    val role: Long,
    val uid: Long,
    val name: String,
)

data class DailyPatrolFact(
    val uid: Long,
    val occurredAt: Instant,
)

enum class ActivityResolution {
    UNRESOLVED,
    UNIQUE_ROSTER_NAME,
    MEMBERSHIP_CORRELATION,
    EXACT_UPDATE,
}

enum class MemberEventType {
    JOINED,
    REJOINED,
    LEFT,
    REMOVED,
    RENAMED,
}

enum class EvidencePrecision {
    EXACT,
    INFERRED,
    AMBIGUOUS,
    UNKNOWN,
    MANUAL,
}

enum class EvidenceSource {
    SNAPSHOT,
    GAME_UPDATES,
    MANUAL,
    LEGACY_IMPORT,
}

enum class DailyEvidence {
    NO_OBSERVATION,
    ATTRIBUTED,
    PARTIAL_DAY,
    INCOMPLETE_BOUNDARY,
    SPARSE_INFERRED,
    MANUAL,
}

enum class MetricCertainty {
    UNKNOWN,
    LOWER_BOUND,
    EXACT,
}

/** EXACT when a value's bounds coincide, otherwise only its minimum is known. */
internal fun rangeCertainty(minimum: Number, maximum: Number): MetricCertainty =
    if (minimum.toLong() == maximum.toLong()) MetricCertainty.EXACT else MetricCertainty.LOWER_BOUND

/**
 * A manual weekly note. [eventId] and [isAutomatic] describe retired automatic event notes,
 * which the schema-6 upgrade deleted; they stay null and false but remain in the row and the
 * weekly-history wire format so stored revisions and their fingerprints stay compatible.
 */
data class WeeklyNote(
    val id: Long,
    val periodStart: LocalDate,
    val gameDay: LocalDate,
    val text: String,
    val eventId: Long?,
    val isAutomatic: Boolean,
)

/** Complete immutable state needed to render or share one historical weekly table. */
data class WeeklyTableRevision(
    val report: WeeklyReportBuilder.Report,
    val membershipEvents: List<MemberEvent>,
    val notes: List<WeeklyNote>,
    val memberNamesByUid: Map<Long, String>,
    val memberPrivateNotesByUid: Map<Long, String>,
)

data class WeeklyCellOverride(
    val uid: Long,
    val periodStart: LocalDate,
    val gameDay: LocalDate,
    val meritDelta: Long?,
    val scoreDelta: Long?,
    val attempts: Int?,
    val attended: Boolean?,
    val dailyPatrol: Boolean?,
)
