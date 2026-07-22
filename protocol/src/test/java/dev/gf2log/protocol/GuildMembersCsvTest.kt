package dev.gf2log.protocol

import dev.gf2log.protocol.model.GuildMember
import org.junit.Assert.assertEquals
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
}
