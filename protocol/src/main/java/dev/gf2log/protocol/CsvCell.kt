package dev.gf2log.protocol

/** Encodes values as CSV cells and optionally prevents spreadsheet formula execution. */
object CsvCell {
    // Function Name: escape
    // Description:
    // - Applies RFC-style CSV quoting for delimiters, quotes, and line breaks.
    // - Prefixes spreadsheet-dangerous text with an apostrophe for explicit user exports.
    // Parameters:
    // - value: Cell value to encode; null becomes an empty cell.
    // - spreadsheetSafe: Whether formula-like text must be neutralized for spreadsheet opening.
    // Returns:
    // - A single encoded CSV cell that preserves the displayed value without executing a formula.
    fun escape(value: Any?, spreadsheetSafe: Boolean = true): String {
        val text = value?.toString().orEmpty()
        val safeText = if (
            spreadsheetSafe && value is CharSequence && canStartSpreadsheetFormula(text)
        ) {
            "'$text"
        } else {
            text
        }
        if (safeText.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return safeText
        return "\"${safeText.replace("\"", "\"\"")}\""
    }

    private fun canStartSpreadsheetFormula(value: String): Boolean {
        val firstMeaningful = value.firstOrNull { !it.isWhitespace() } ?: return false
        return firstMeaningful == '=' || firstMeaningful == '+' ||
            firstMeaningful == '-' || firstMeaningful == '@'
    }
}
