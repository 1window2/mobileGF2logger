package dev.gf2log.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvCellTest {
    @Test
    fun neutralizesEverySpreadsheetFormulaPrefixIncludingLeadingWhitespace() {
        listOf(
            "=1+1",
            "+cmd",
            "-2+3",
            "@SUM(A1)",
            "  =HYPERLINK(\"x\")",
            "\t=WEBSERVICE(\"x\")",
            "\r@SUM(A1)",
        ).forEach { value ->
            val escaped = CsvCell.escape(value)
            val decoded = escaped.removeSurrounding("\"").replace("\"\"", "\"")

            assertEquals("'$value", decoded)
        }
    }

    @Test
    fun internalCsvCanPreserveFormulaLikeTextWithoutNeutralization() {
        assertEquals("=literal-name", CsvCell.escape("=literal-name", spreadsheetSafe = false))
    }

    @Test
    fun spreadsheetPrefixIsReversibleForEveryText() {
        listOf(
            "=x", "'=x", "''=x", "  -y", "'  @z", "'abc", "abc", "", "'", " '=x", "\r+1",
        ).forEach { value ->
            val escaped = CsvCell.escape(value).removeSurrounding("\"").replace("\"\"", "\"")

            assertEquals(value, CsvCell.unescapeSpreadsheet(escaped))
        }
        assertEquals("''=x", CsvCell.escape("'=x"))
        assertEquals("'abc", CsvCell.escape("'abc"))
    }
}
