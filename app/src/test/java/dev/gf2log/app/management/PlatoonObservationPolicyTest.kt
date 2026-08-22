package dev.gf2log.app.management

import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonActivityEntry
import dev.gf2log.protocol.model.PlatoonUpdateEntry
import dev.gf2log.protocol.model.PlatoonUpdateMember
import dev.gf2log.protocol.model.PlatoonUpdatesData
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatoonObservationPolicyTest {
    @Test
    fun `activity accepts only distinct observations with member names`() {
        val valid = activity(name = "Member")

        assertEquals(
            listOf(valid),
            PlatoonObservationPolicy.activity(
                listOf(valid, valid.copy(), activity(name = "  ")),
            ),
        )
    }

    @Test
    fun `activity bounds untrusted names and observation count`() {
        val observations = (1..(PlatoonObservationPolicy.MAX_ACTIVITY_OBSERVATIONS + 1)).map {
            activity(name = "Member $it").copy(actionId = it.toLong())
        }
        val oversizedName = activity(
            name = "x".repeat(PlatoonObservationPolicy.MAX_ACTIVITY_MEMBER_NAME_LENGTH + 1),
        )

        val accepted = PlatoonObservationPolicy.activity(listOf(oversizedName) + observations)

        assertEquals(PlatoonObservationPolicy.MAX_ACTIVITY_OBSERVATIONS, accepted.size)
        assertEquals(observations.take(PlatoonObservationPolicy.MAX_ACTIVITY_OBSERVATIONS), accepted)
    }

    @Test
    fun `activity payload exposes timestamps only from accepted observations`() {
        val accepted = PlatoonObservationPolicy.activity(
            PlatoonActivityData(
                summaries = emptyList(),
                entries = listOf(
                    PlatoonActivityEntry(1u, 1u, 0u, "Rejected action"),
                    PlatoonActivityEntry(1u, 2u, 1u, " "),
                    PlatoonActivityEntry(1u, 3u, 1u, "Accepted"),
                ),
            ),
        )

        assertEquals(listOf(Instant.ofEpochSecond(3)), accepted.map { it.occurredAt })
    }

    @Test
    fun `updates reject empty member lists and deduplicate accepted observations`() {
        val valid = update(
            occurredAt = Instant.parse("2026-07-31T00:00:02Z"),
            members = listOf(member(uid = 2)),
        )

        assertEquals(
            listOf(valid),
            PlatoonObservationPolicy.updates(
                listOf(valid, valid.copy(), update(members = emptyList())),
            ),
        )
    }

    @Test
    fun `updates reject unsupported kinds that cannot persist management facts`() {
        assertEquals(
            emptyList<PlatoonUpdateObservation>(),
            PlatoonObservationPolicy.updates(
                listOf(
                    update(
                        kind = 99,
                        members = listOf(member(uid = 1)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `updates preserve distinct entries in chronological order`() {
        val later = update(
            occurredAt = Instant.parse("2026-07-31T00:00:02Z"),
            members = listOf(member(uid = 2)),
        )
        val earlier = update(
            occurredAt = Instant.parse("2026-07-31T00:00:01Z"),
            members = listOf(member(uid = 1)),
        )

        assertEquals(
            listOf(earlier, later),
            PlatoonObservationPolicy.updates(listOf(later, earlier)),
        )
    }

    @Test
    fun `updates payload excludes rejected timestamps and bounds accepted observations`() {
        val entries = (1u..(PlatoonObservationPolicy.MAX_UPDATE_OBSERVATIONS + 1).toUInt())
            .map { timestamp ->
                PlatoonUpdateEntry(
                    kind = PlatoonUpdateSemantics.KIND_JOIN.toUInt(),
                    members = listOf(PlatoonUpdateMember(1u, timestamp, "Member $timestamp")),
                    occurredAt = timestamp,
                )
            }
        val accepted = PlatoonObservationPolicy.updates(
            PlatoonUpdatesData(
                listOf(
                    PlatoonUpdateEntry(99u, emptyList(), 4_000u),
                    PlatoonUpdateEntry(
                        PlatoonUpdateSemantics.KIND_JOIN.toUInt(),
                        listOf(PlatoonUpdateMember(1u, 0u, "Rejected UID")),
                        5_000u,
                    ),
                ) + entries,
            ),
        )

        assertEquals(PlatoonObservationPolicy.MAX_UPDATE_OBSERVATIONS, accepted.size)
        assertEquals(Instant.ofEpochSecond(1), accepted.first().occurredAt)
        assertEquals(
            Instant.ofEpochSecond(PlatoonObservationPolicy.MAX_UPDATE_OBSERVATIONS.toLong()),
            accepted.last().occurredAt,
        )
    }

    @Test
    fun `weekly history work is capped after distinct week mapping`() {
        val zone = ZoneOffset.UTC
        val instants = generateSequence(Instant.parse("2020-01-06T05:00:00Z")) {
            it.plusSeconds(7 * 24 * 60 * 60L)
        }.take(WeeklyHistoryWorkPolicy.MAX_CHANGED_WEEKS_PER_INGEST + 50)

        val periods = WeeklyHistoryWorkPolicy.changedPeriodStarts(instants, zone)

        assertEquals(WeeklyHistoryWorkPolicy.MAX_CHANGED_WEEKS_PER_INGEST, periods.size)
        assertEquals(periods.size, periods.distinct().size)
    }

    private fun activity(name: String) = PlatoonActivityObservation(
        occurredAt = Instant.parse("2026-07-31T00:00:00Z"),
        actionId = 1,
        kind = 2,
        memberName = name,
    )

    private fun update(
        kind: Long = PlatoonUpdateSemantics.KIND_JOIN,
        occurredAt: Instant = Instant.parse("2026-07-31T00:00:00Z"),
        members: List<PlatoonUpdateMemberObservation>,
    ) = PlatoonUpdateObservation(
        kind = kind,
        occurredAt = occurredAt,
        members = members,
    )

    private fun member(uid: Long) = PlatoonUpdateMemberObservation(
        role = 1,
        uid = uid,
        name = "Member $uid",
    )
}
