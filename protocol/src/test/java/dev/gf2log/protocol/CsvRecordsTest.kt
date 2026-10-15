package dev.gf2log.protocol

import dev.gf2log.protocol.internal.CsvRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvRecordsTest {
    @Test
    fun quotedDelimitersLineBreaksAndEscapedQuotesStayInOneField() {
        assertEquals(
            listOf(listOf("a,b", "line\nbreak", "say \"hi\""), listOf("2", "", "")),
            CsvRecords.parse("\"a,b\",\"line\r\nbreak\",\"say \"\"hi\"\"\"\r\n2,,", 3, 2, 32),
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
