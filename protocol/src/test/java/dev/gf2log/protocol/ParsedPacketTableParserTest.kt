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

    @Test
    fun rejectsTablesWhoseRowCountWouldCreateUnboundedViews() {
        val rows = List(ParsedPacketTableParser.MAX_ROWS + 1) { "$it,Member" }
        val content = metadata() + (listOf("uid,name") + rows).joinToString("\n")

        assertNull(ParsedPacketTableParser.parse(content))
    }

    @Test
    fun rejectsTablesWhoseColumnOrCellBudgetsWouldCreateUnboundedViews() {
        val tooManyColumns = List(ParsedPacketTableParser.MAX_COLUMNS + 1) { "column$it" }
            .joinToString(",")
        val wideContent = metadata() + "$tooManyColumns\n"
        val header = List(16) { "c$it" }.joinToString(",")
        val rows = List(128) { index -> List(16) { "$index" }.joinToString(",") }
        val tooManyCells = metadata() + (listOf(header) + rows).joinToString("\n")

        assertNull(ParsedPacketTableParser.parse(wideContent))
        assertNull(ParsedPacketTableParser.parse(tooManyCells))
    }

    @Test
    fun rejectsOversizedHistoryAndIndividualCells() {
        val largeHistory = metadata() + "a\n" +
            "x".repeat(ParsedPacketTableParser.MAX_CONTENT_CHARS)
        val largeCell = metadata() + "a\n" +
            "x".repeat(ParsedPacketTableParser.MAX_CELL_CHARS + 1)

        assertNull(ParsedPacketTableParser.parse(largeHistory))
        assertNull(ParsedPacketTableParser.parse(largeCell))
    }

    private fun metadata(): String =
        "capturedAt=2026-08-08T00:00:00Z\nmessageId=1\npayloadType=21917\n\n"
}
