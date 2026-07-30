package dev.gf2log.app.management

internal object PlatoonObservationPolicy {
    fun activity(
        observations: List<PlatoonActivityObservation>,
    ): List<PlatoonActivityObservation> = observations
        .filter { it.memberName.isNotBlank() }
        .distinctBy { listOf(it.occurredAt, it.actionId, it.kind, it.memberName) }

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
