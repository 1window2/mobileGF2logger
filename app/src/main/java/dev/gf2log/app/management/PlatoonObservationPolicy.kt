package dev.gf2log.app.management

import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import java.time.Instant

internal object PlatoonObservationPolicy {
    const val MAX_ACTIVITY_OBSERVATIONS = 250
    const val MAX_UPDATE_OBSERVATIONS = 250
    const val MAX_ACTIVITY_MEMBER_NAME_LENGTH = 128

    fun activity(data: PlatoonActivityData): List<PlatoonActivityObservation> = activity(
        data.entries.mapNotNull { entry ->
            if (
                entry.occurredAt == 0u ||
                entry.actionId == 0u ||
                entry.memberName.isBlank() ||
                entry.memberName.length > MAX_ACTIVITY_MEMBER_NAME_LENGTH
            ) {
                null
            } else {
                PlatoonActivityObservation(
                    occurredAt = Instant.ofEpochSecond(entry.occurredAt.toLong()),
                    actionId = entry.actionId.toLong(),
                    kind = entry.kind.toLong(),
                    memberName = entry.memberName,
                )
            }
        },
    )

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

    fun updates(data: PlatoonUpdatesData): List<PlatoonUpdateObservation> = updates(
        data.entries.mapNotNull { entry ->
            if (entry.occurredAt == 0u || entry.kind == 0u) {
                null
            } else {
                PlatoonUpdateObservation(
                    kind = entry.kind.toLong(),
                    occurredAt = Instant.ofEpochSecond(entry.occurredAt.toLong()),
                    members = entry.members.mapNotNull { member ->
                        if (member.uid == 0u) {
                            null
                        } else {
                            PlatoonUpdateMemberObservation(
                                role = member.role.toLong(),
                                uid = member.uid.toLong(),
                                name = member.name,
                            )
                        }
                    },
                )
            }
        },
    )

    fun updates(
        observations: List<PlatoonUpdateObservation>,
    ): List<PlatoonUpdateObservation> = observations.asSequence()
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
        .take(MAX_UPDATE_OBSERVATIONS)
        .sortedBy(PlatoonUpdateObservation::occurredAt)
        .toList()
}
