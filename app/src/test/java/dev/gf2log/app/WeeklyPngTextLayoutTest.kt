package dev.gf2log.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyPngTextLayoutTest {
    /** A monospace "font": every character is one unit wide. */
    private val width = 20
    private fun fitCount(text: String) = minOf(text.length, width)
    private fun fits(text: String) = text.length <= width

    // Roughly the 16 px body paint: ascent 17, line spacing 19, cell padding 9.
    private val padding = 9
    private val ascent = 17f
    private val spacing = 19f

    @Test
    fun `a twenty-line note is cut to six lines ending in an ellipsis`() {
        val note = (1..20).joinToString(" ") { "word%02d".format(it) + " filler" }
        val lines = WeeklyPngTextLayout.wrap(note, ::fitCount)
        assertTrue(lines.size > 6)

        val drawn = WeeklyPngTextLayout.truncate(lines, 6, ::fits)

        assertEquals(6, drawn.size)
        assertEquals(lines.take(5), drawn.take(5))
        assertTrue(drawn.last().endsWith(WeeklyPngTextLayout.ELLIPSIS))
        assertTrue(fits(drawn.last()))
    }

    @Test
    fun `text that fits is drawn whole without an ellipsis`() {
        val lines = WeeklyPngTextLayout.wrap("short note", ::fitCount)

        assertEquals(listOf("short note"), WeeklyPngTextLayout.truncate(lines, 3, ::fits))
    }

    @Test
    fun `a row tall enough for its longest cell draws every line`() {
        (1..6).forEach { count ->
            val height = WeeklyPngTextLayout.heightFor(count, padding, ascent, spacing)
            assertEquals(count, WeeklyPngTextLayout.linesFitting(height, padding, ascent, spacing))
            // heightFor is the smallest such height.
            assertEquals(count - 1, WeeklyPngTextLayout.linesFitting(height - 1, padding, ascent, spacing))
        }
    }

    @Test
    fun `the old fixed row heights fit three and five lines`() {
        // 88 px rows silently dropped everything after line 3, and 112 px rows after line 5.
        assertEquals(3, WeeklyPngTextLayout.linesFitting(88, padding, ascent, spacing))
        assertEquals(5, WeeklyPngTextLayout.linesFitting(112, padding, ascent, spacing))
        assertTrue(WeeklyPngTextLayout.heightFor(6, padding, ascent, spacing) > 112)
    }

    @Test
    fun `a 400 character note grows its row to the six line cap`() {
        val note = "lorem ipsum ".repeat(34).trim()
        val lines = WeeklyPngTextLayout.wrap(note, ::fitCount)
        val height = WeeklyPngTextLayout.heightFor(minOf(lines.size, 6), padding, ascent, spacing)

        assertEquals(6, WeeklyPngTextLayout.linesFitting(height, padding, ascent, spacing))
    }

    @Test
    fun `an ellipsis never splits a surrogate pair`() {
        val emoji = "😀"
        val lines = listOf("a".repeat(19) + emoji, "more")

        val drawn = WeeklyPngTextLayout.truncate(lines, 1) { it.length <= 20 }

        assertEquals("a".repeat(19) + WeeklyPngTextLayout.ELLIPSIS, drawn.single())
    }

    @Test
    fun `wrapping prefers the last space and keeps explicit lines`() {
        assertEquals(
            listOf("M 90", "S ≥31635 A ≥2"),
            WeeklyPngTextLayout.wrap("M 90\nS ≥31635 A ≥2", ::fitCount),
        )
        assertEquals(listOf("aaaa bbbb", "cccc"), WeeklyPngTextLayout.wrap("aaaa bbbb cccc") { minOf(it.length, 12) })
    }
}
