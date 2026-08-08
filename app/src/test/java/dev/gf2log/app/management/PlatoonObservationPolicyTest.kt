package dev.gf2log.app.management

import java.time.Instant
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
