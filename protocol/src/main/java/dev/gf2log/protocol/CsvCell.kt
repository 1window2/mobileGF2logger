package dev.gf2log.protocol

/** Encodes values as CSV cells and optionally prevents spreadsheet formula execution. */
object CsvCell {
    // Function Name: escape
    // Description:
    // - Applies RFC-style CSV quoting for delimiters, quotes, and line breaks.
    // - Prefixes spreadsheet-dangerous text with an apostrophe for explicit user exports.
    // - Also prefixes text that only becomes formula-like once leading apostrophes are removed,
    //   so [unescapeSpreadsheet] can undo exactly one prefix and the export stays reversible.
    // Parameters:
    // - value: Cell value to encode; null becomes an empty cell.
    // - spreadsheetSafe: Whether formula-like text must be neutralized for spreadsheet opening.
    // Returns:
    // - A single encoded CSV cell that preserves the displayed value without executing a formula.
    fun escape(value: Any?, spreadsheetSafe: Boolean = true): String {
        val text = value?.toString().orEmpty()
        val safeText = if (
            spreadsheetSafe && value is CharSequence &&
            canStartSpreadsheetFormula(text.trimStart('\''))
        ) {
            "'$text"
        } else {
            text
        }
        if (safeText.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return safeText
        return "\"${safeText.replace("\"", "\"\"")}\""
    }

    /**
     * Inverts the apostrophe [escape] adds to decoded cell text: removes exactly one leading
     * apostrophe when formula-like text follows the apostrophes, and leaves anything else alone.
     */
    internal fun unescapeSpreadsheet(text: String): String =
        if (text.startsWith('\'') && canStartSpreadsheetFormula(text.trimStart('\''))) {
            text.substring(1)
        } else {
            text
        }

    private fun canStartSpreadsheetFormula(value: String): Boolean {
        val firstMeaningful = value.firstOrNull { !it.isWhitespace() } ?: return false
        return firstMeaningful == '=' || firstMeaningful == '+' ||
            firstMeaningful == '-' || firstMeaningful == '@'
    }
}
