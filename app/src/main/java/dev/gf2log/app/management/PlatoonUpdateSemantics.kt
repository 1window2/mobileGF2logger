package dev.gf2log.app.management

/**
 * Interprets the ordered member list carried by payload 21960.
 *
 * Removal entries include both the actor and the affected member. The affected
 * member is the final member in the entry (the CSV formatter exposes this order
 * as memberIndex), so management logic must never apply the boundary to every
 * listed member.
 */
internal object PlatoonUpdateSemantics {
    const val KIND_JOIN = 3L
    const val KIND_WITHDRAW = 4L
    const val KIND_REMOVED = 5L
    const val KIND_DAILY_PATROL = 8L

    fun effect(kind: Long): PlatoonUpdateEffect = when (kind) {
        KIND_JOIN -> PlatoonUpdateEffect.JOIN
        KIND_WITHDRAW -> PlatoonUpdateEffect.WITHDRAW
        KIND_REMOVED -> PlatoonUpdateEffect.REMOVED
        KIND_DAILY_PATROL -> PlatoonUpdateEffect.DAILY_PATROL
        else -> PlatoonUpdateEffect.IGNORE
    }

    fun affectedMembers(
        kind: Long,
        members: List<PlatoonUpdateMemberObservation>,
    ): List<PlatoonUpdateMemberObservation> = when (effect(kind)) {
        PlatoonUpdateEffect.JOIN,
        PlatoonUpdateEffect.DAILY_PATROL,
        -> members

        PlatoonUpdateEffect.WITHDRAW,
        PlatoonUpdateEffect.REMOVED,
        -> members.lastOrNull()?.let(::listOf).orEmpty()

        PlatoonUpdateEffect.IGNORE -> emptyList()
    }
}

internal enum class PlatoonUpdateEffect {
    JOIN,
    WITHDRAW,
    REMOVED,
    DAILY_PATROL,
    IGNORE,
}
