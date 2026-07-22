package dev.gf2log.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsedPacketTableParserTest {
    @Test
    fun parsesFormattedPacketCsvIncludingQuotedNames() {
        val content = """
            capturedAt=2026-07-22T12:25:44Z
            messageId=42
            payloadType=21917

            uid,name,level
            1,Commander,60
            2,"A, ""quoted""
            name",59
        """.trimIndent()

        val table = ParsedPacketTableParser.parse(content)!!

        assertEquals(listOf("uid", "name", "level"), table.header)
        assertEquals(listOf("1", "Commander", "60"), table.rows[0])
        assertEquals(listOf("2", "A, \"quoted\"\nname", "59"), table.rows[1])
    }

    @Test
    fun rejectsContentWithoutTheMetadataSeparator() {
        assertNull(ParsedPacketTableParser.parse("uid,name\n1,Commander"))
    }
}
