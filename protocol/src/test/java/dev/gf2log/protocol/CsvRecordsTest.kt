package dev.gf2log.protocol

import dev.gf2log.protocol.internal.CsvRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvRecordsTest {
    @Test
    fun quotedDelimitersLineBreaksAndEscapedQuotesStayInOneField() {
        assertEquals(
            listOf(listOf("a,b", "line\r\nbreak", "say \"hi\""), listOf("2", "", "")),
            CsvRecords.parse("\"a,b\",\"line\r\nbreak\",\"say \"\"hi\"\"\"\r\n2,,", 3, 2, 32),
        )
    }

    @Test
    fun quotedCarriageReturnsArePreservedWhileUnquotedOnesEndRecords() {
        assertEquals(listOf(listOf("a\rb")), CsvRecords.parse("\"a\rb\"", 1, 1, 8))
        assertEquals(listOf(listOf("a\r\nb")), CsvRecords.parse("\"a\r\nb\"\r\n", 1, 1, 8))
        assertEquals(
            listOf(listOf("x"), listOf("y"), listOf("z")),
            CsvRecords.parse("x\r\ny\rz\n", 1, 3, 8),
        )
    }

    @Test
    fun everyBoundAndUnbalancedQuoteRejectsTheDocument() {
        assertNull(CsvRecords.parse("a,b,c", maxColumns = 2, maxRecords = 1, maxFieldChars = 8))
        assertNull(CsvRecords.parse("a\nb\nc", maxColumns = 1, maxRecords = 2, maxFieldChars = 8))
        assertNull(CsvRecords.parse("abcdef", maxColumns = 1, maxRecords = 1, maxFieldChars = 5))
        assertNull(CsvRecords.parse("\"open", maxColumns = 1, maxRecords = 1, maxFieldChars = 8))
    }
}
