package dev.gf2log.protocol

import dev.gf2log.protocol.model.GuildMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuildMembersCsvTest {
    @Test
    fun guildMemberMatchesReferenceColumnOrder() {
        val member = GuildMember(
            uid = 258857u,
            name = "카논",
            level = 60u,
            weeklyMerit = 3750u,
            totalMerit = 313832u,
            highScore = 10398u,
            totalScore = 51661u,
            lastLogin = 1784639347u,
        )

        assertEquals(
            "258857,카논,60,3750,313832,10398,51661,1784639347,2026-07-21T19:11:09Z",
            GuildMembersCsv.row(member, "2026-07-21T19:11:09Z"),
        )
    }

    @Test
    fun namesAreCsvEscaped() {
        val member = GuildMember(1u, "A, \"B\"", 2u, 3u, 4u, 5u, 6u, 7u)

        assertEquals(
            "1,\"A, \"\"B\"\"\",2,3,4,5,6,7,2026-01-01T00:00:00Z",
            GuildMembersCsv.row(member, "2026-01-01T00:00:00Z"),
        )
    }

    @Test
    fun parsesACompleteSnapshot() {
        val content = listOf(
            GuildMembersCsv.HEADER,
            "258857,\"Kanon, \"\"Leader\"\"\",60,3750,313832,10398,51661,1784639347,2026-07-21T19:11:09Z",
            "1025106,Crios,60,3761,21564,10545,52165,1784644895,2026-07-21T19:11:09Z",
        ).joinToString("\n")

        val parsed = GuildMembersCsv.parse(content)

        assertEquals("2026-07-21T19:11:09Z", parsed!!.logTime)
        assertEquals(2, parsed.members.size)
        assertEquals("Kanon, \"Leader\"", parsed.members.first().name)
        assertEquals(1025106u, parsed.members.last().uid)
    }

    @Test
    fun rejectsMixedCaptureTimes() {
        val content = """
            ${GuildMembersCsv.HEADER}
            1,One,60,1,1,1,1,1,2026-07-21T19:11:09Z
            2,Two,60,1,1,1,1,1,2026-07-21T19:12:09Z
        """.trimIndent()

        assertNull(GuildMembersCsv.parse(content))
    }
}
