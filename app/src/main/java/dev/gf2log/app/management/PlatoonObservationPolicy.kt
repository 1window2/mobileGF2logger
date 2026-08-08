package dev.gf2log.app.management

internal object PlatoonObservationPolicy {
    const val MAX_ACTIVITY_OBSERVATIONS = 250
    const val MAX_ACTIVITY_MEMBER_NAME_LENGTH = 128

    fun activity(
        observations: List<PlatoonActivityObservation>,
    ): List<PlatoonActivityObservation> = observations.asSequence()
        .filter {
            it.memberName.isNotBlank() &&
                it.memberName.length <= MAX_ACTIVITY_MEMBER_NAME_LENGTH
        }
        .distinctBy { listOf(it.occurredAt, it.actionId, it.kind, it.memberName) }
        .take(MAX_ACTIVITY_OBSERVATIONS)
        .toList()

    fun updates(
        observations: List<PlatoonUpdateObservation>,
    ): List<PlatoonUpdateObservation> = observations
        .filter {
            it.members.isNotEmpty() &&
                PlatoonUpdateSemantics.effect(it.kind) != PlatoonUpdateEffect.IGNORE
        }
        .distinctBy { observation ->
            listOf(
                observation.kind,
                observation.occurredAt,
                observation.members.map { listOf(it.role, it.uid, it.name) },
            )
        }
        .sortedBy(PlatoonUpdateObservation::occurredAt)
}
