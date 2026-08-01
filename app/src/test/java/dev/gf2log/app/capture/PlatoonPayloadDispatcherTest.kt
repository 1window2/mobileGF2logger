package dev.gf2log.app.capture

import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonActivityEntry
import dev.gf2log.protocol.model.PlatoonUpdateEntry
import dev.gf2log.protocol.model.PlatoonUpdateMember
import dev.gf2log.protocol.model.PlatoonUpdatesData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonPayloadDispatcherTest {
    @Test
    fun routesRequiredPayloadsAndAlwaysSignalsTheRosterWriter() {
        val routed = mutableListOf<String>()
        val dispatcher = PlatoonPayloadDispatcher(
            onMembers = { _, _ -> routed += "members"; null },
            onActivity = { routed += "activity"; true },
            onUpdates = { routed += "updates"; true },
        )

        val members = dispatcher.dispatch(membersPayload())
        val activity = dispatcher.dispatch(activityPayload())
        val updates = dispatcher.dispatch(updatesPayload())

        assertEquals(
            listOf("members", "activity", "members", "updates", "members"),
            routed,
        )
        assertTrue(members.members.getOrThrow() == null)
        assertNull(members.activity)
        assertNull(members.updates)
        assertTrue(activity.activity?.getOrThrow() == true)
        assertTrue(activity.members.getOrThrow() == null)
        assertTrue(updates.updates?.getOrThrow() == true)
        assertTrue(updates.members.getOrThrow() == null)
    }

    @Test
    fun handlerFailureDoesNotSuppressTheRosterBoundarySignal() {
        val routed = mutableListOf<String>()
        val dispatcher = PlatoonPayloadDispatcher(
            onMembers = { _, _ -> routed += "members"; null },
            onActivity = { routed += "activity"; error("database unavailable") },
            onUpdates = { routed += "updates"; true },
        )

        val result = dispatcher.dispatch(activityPayload())

        assertEquals(listOf("activity", "members"), routed)
        assertTrue(result.activity?.isFailure == true)
        assertTrue(result.members.isSuccess)
        assertNull(result.updates)
    }

    private fun membersPayload() = ParsedPayload(
        messageId = 1,
        payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
        isEndOfMessage = true,
        data = GuildMembersData(
            listOf(GuildMember(1u, "Member", 60u, 90u, 900u, 0u, 0u, 1u)),
        ),
    )

    private fun activityPayload() = ParsedPayload(
        messageId = 2,
        payloadType = Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
        isEndOfMessage = true,
        data = PlatoonActivityData(
            summaries = emptyList(),
            entries = listOf(PlatoonActivityEntry(1u, 1u, 802001u, "Member")),
        ),
    )

    private fun updatesPayload() = ParsedPayload(
        messageId = 3,
        payloadType = Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
        isEndOfMessage = true,
        data = PlatoonUpdatesData(
            listOf(PlatoonUpdateEntry(3u, listOf(PlatoonUpdateMember(0u, 1u, "Member")), 1u)),
        ),
    )
}
