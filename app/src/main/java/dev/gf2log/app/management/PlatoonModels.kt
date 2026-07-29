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

data class MembershipTenure(
    val id: Long,
    val uid: Long,
    val joinedAt: Instant?,
    val leftAt: Instant?,
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
    val tenures: List<MembershipTenure>,
)

data class MemberEvent(
    val id: Long,
    val uid: Long,
    val type: MemberEventType,
    val occurredAt: Instant?,
    val observedAt: Instant,
    val precision: EvidencePrecision,
    val source: EvidenceSource,
    val note: String,
)

data class PlatoonActivityFact(
    val id: Long,
    val occurredAt: Instant,
    val actionId: Long,
    val kind: Long,
    val memberName: String,
    val capturedAt: Instant,
    val resolvedUid: Long?,
    val resolution: ActivityResolution,
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

data class DailyActivity(
    val uid: Long,
    val gameDay: LocalDate,
    val meritDelta: Long,
    val scoreDelta: Long,
    val inference: ActivityInference.Result,
)

enum class DailyEvidence {
    NO_OBSERVATION,
    ATTRIBUTED,
    PARTIAL_DAY,
    INCOMPLETE_BOUNDARY,
    SPARSE_INFERRED,
    MANUAL,
}

data class WeeklyNote(
    val id: Long,
    val periodStart: LocalDate,
    val gameDay: LocalDate,
    val text: String,
    val eventId: Long?,
    val isAutomatic: Boolean,
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
