package dev.gf2log.app.capture

import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonUpdatesData

internal class PlatoonPayloadDispatcher(
    private val onMembers: (ParsedPayload, Boolean) -> GuildMembersCsvWriter.SaveResult?,
    private val onActivity: (PlatoonActivityData) -> Boolean,
    private val onUpdates: (PlatoonUpdatesData) -> Boolean,
) {
    fun dispatch(payload: ParsedPayload, flowEnded: Boolean = false): Results {
        val activity = (payload.data as? PlatoonActivityData)?.let { data ->
            runCatching { onActivity(data) }
        }
        val updates = (payload.data as? PlatoonUpdatesData)?.let { data ->
            runCatching { onUpdates(data) }
        }
        // Every decoded payload reaches the writer so a non-roster payload can
        // close an unterminated roster continuation before later data arrives.
        val members = runCatching { onMembers(payload, flowEnded) }
        return Results(members, activity, updates)
    }

    data class Results(
        val members: Result<GuildMembersCsvWriter.SaveResult?>,
        val activity: Result<Boolean>?,
        val updates: Result<Boolean>?,
    )
}
