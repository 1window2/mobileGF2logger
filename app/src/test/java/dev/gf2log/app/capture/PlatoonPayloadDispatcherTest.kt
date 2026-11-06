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
import java.time.Instant

class PlatoonPayloadDispatcherTest {
    @Test
    fun routesEachRequiredPayloadOnlyToItsHandler() {
        val routed = mutableListOf<String>()
        val dispatcher = PlatoonPayloadDispatcher(
            onMembers = { _, at -> routed += "members@$at"; null },
            onActivity = { _, at -> routed += "activity@$at"; true },
            onUpdates = { _, at -> routed += "updates@$at"; true },
        )

        // Each handler dates its record by when the payload arrived, however late it is routed.
        val members = dispatcher.dispatch(membersPayload(), Instant.parse("2026-07-22T04:50:00Z"))
        val activity = dispatcher.dispatch(activityPayload(), Instant.parse("2026-07-22T04:51:00Z"))
        val updates = dispatcher.dispatch(updatesPayload(), Instant.parse("2026-07-22T04:52:00Z"))

        assertEquals(
            listOf(
                "members@2026-07-22T04:50:00Z",
                "activity@2026-07-22T04:51:00Z",
                "updates@2026-07-22T04:52:00Z",
            ),
            routed,
        )
        assertTrue(members.members?.getOrThrow() == null)
        assertNull(members.activity)
        assertNull(members.updates)
        assertTrue(activity.activity?.getOrThrow() == true)
        assertNull(activity.members)
        assertTrue(updates.updates?.getOrThrow() == true)
        assertNull(updates.members)
    }

    @Test
    fun handlerFailureIsReturnedInsteadOfThrown() {
        val routed = mutableListOf<String>()
        val dispatcher = PlatoonPayloadDispatcher(
            onMembers = { _, _ -> routed += "members"; null },
            onActivity = { _, _ -> routed += "activity"; error("database unavailable") },
            onUpdates = { _, _ -> routed += "updates"; true },
        )

        val result = dispatcher.dispatch(activityPayload(), Instant.EPOCH)

        assertEquals(listOf("activity"), routed)
        assertTrue(result.activity?.isFailure == true)
        assertNull(result.members)
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
