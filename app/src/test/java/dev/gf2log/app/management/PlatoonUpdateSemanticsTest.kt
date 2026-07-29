package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatoonUpdateSemanticsTest {
    private val actor = member(uid = 1L, name = "Leader")
    private val target = member(uid = 2L, name = "Member")

    @Test
    fun `removed entry affects only final indexed member`() {
        assertEquals(
            listOf(target),
            PlatoonUpdateSemantics.affectedMembers(
                PlatoonUpdateSemantics.KIND_REMOVED,
                listOf(actor, target),
            ),
        )
    }

    @Test
    fun `voluntary withdrawal affects only final member`() {
        assertEquals(
            listOf(target),
            PlatoonUpdateSemantics.affectedMembers(
                PlatoonUpdateSemantics.KIND_WITHDRAW,
                listOf(actor, target),
            ),
        )
    }

    @Test
    fun `join and Daily Patrol apply to every listed member`() {
        val members = listOf(actor, target)

        assertEquals(
            members,
            PlatoonUpdateSemantics.affectedMembers(
                PlatoonUpdateSemantics.KIND_JOIN,
                members,
            ),
        )
        assertEquals(
            members,
            PlatoonUpdateSemantics.affectedMembers(
                PlatoonUpdateSemantics.KIND_DAILY_PATROL,
                members,
            ),
        )
    }

    @Test
    fun `unknown kinds are preserved by parser but ignored by management`() {
        listOf(1L, 2L, 6L, 7L).forEach { kind ->
            assertEquals(PlatoonUpdateEffect.IGNORE, PlatoonUpdateSemantics.effect(kind))
            assertEquals(
                emptyList<PlatoonUpdateMemberObservation>(),
                PlatoonUpdateSemantics.affectedMembers(kind, listOf(actor, target)),
            )
        }
    }

    private fun member(uid: Long, name: String) = PlatoonUpdateMemberObservation(
        role = 1L,
        uid = uid,
        name = name,
    )
}
