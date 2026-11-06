package dev.gf2log.app.capture

import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import java.time.Instant

/** A decoded payload held for later routing, with the time it arrived from the network. */
internal data class ReceivedPayload(val payload: ParsedPayload, val receivedAt: Instant)

/**
 * Routes each Platoon payload to its handler; one handler's failure never suppresses another.
 * Every handler receives the payload's arrival time, which a held payload keeps until it is routed.
 */
internal class PlatoonPayloadDispatcher(
    private val onMembers: (ParsedPayload, Instant) -> GuildMembersCsvWriter.SaveResult?,
    private val onActivity: (PlatoonActivityData, Instant) -> Boolean,
    private val onUpdates: (PlatoonUpdatesData, Instant) -> Boolean,
) {
    fun dispatch(payload: ParsedPayload, receivedAt: Instant): Results = Results(
        members = payload.takeIf { it.data is GuildMembersData }?.let { runCatching { onMembers(it, receivedAt) } },
        activity = (payload.data as? PlatoonActivityData)?.let { runCatching { onActivity(it, receivedAt) } },
        updates = (payload.data as? PlatoonUpdatesData)?.let { runCatching { onUpdates(it, receivedAt) } },
    )

    data class Results(
        val members: Result<GuildMembersCsvWriter.SaveResult?>?,
        val activity: Result<Boolean>?,
        val updates: Result<Boolean>?,
    )
}
