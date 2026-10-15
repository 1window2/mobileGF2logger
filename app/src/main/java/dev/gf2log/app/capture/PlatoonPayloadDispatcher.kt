package dev.gf2log.app.capture

import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonUpdatesData

/** Routes each Platoon payload to its handler; one handler's failure never suppresses another. */
internal class PlatoonPayloadDispatcher(
    private val onMembers: (ParsedPayload) -> GuildMembersCsvWriter.SaveResult?,
    private val onActivity: (PlatoonActivityData) -> Boolean,
    private val onUpdates: (PlatoonUpdatesData) -> Boolean,
) {
    fun dispatch(payload: ParsedPayload): Results = Results(
        members = payload.takeIf { it.data is GuildMembersData }?.let { runCatching { onMembers(it) } },
        activity = (payload.data as? PlatoonActivityData)?.let { runCatching { onActivity(it) } },
        updates = (payload.data as? PlatoonUpdatesData)?.let { runCatching { onUpdates(it) } },
    )

    data class Results(
        val members: Result<GuildMembersCsvWriter.SaveResult?>?,
        val activity: Result<Boolean>?,
        val updates: Result<Boolean>?,
    )
}
