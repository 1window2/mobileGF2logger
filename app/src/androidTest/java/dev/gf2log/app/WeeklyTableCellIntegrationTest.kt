package dev.gf2log.app

import android.content.Context
import android.content.res.Configuration
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.management.DailyEvidence
import dev.gf2log.app.management.MetricCertainty
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.settings.WeeklyCutlines
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeeklyTableCellIntegrationTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val week = LocalDate.of(2026, 10, 12)

    @Test
    fun gunsmokeAttemptsStayVisibleAtTwiceTheFontSize() {
        val context = scaled(2f)
        val table = WeeklyTable(context, isGunsmokeWeek = true, zone = ZoneOffset.UTC, cutlines = WeeklyCutlines())
        val row = table.MemberRow()
        row.bind(member(), rankValue = 1, edits = emptyMap()) {}
        measure(row.view, context, table.rowHeight)

        val caption = cells(row.view).first().getChildAt(1) as TextView
        val layout = caption.layout
        val last = layout.lineCount - 1
        assertTrue(layout.lineCount <= caption.maxLines)
        assertEquals(0, layout.getEllipsisCount(last))
        assertEquals(caption.text.length, layout.getLineEnd(last))
        assertTrue(caption.text.toString().endsWith("≥2"))
    }

    @Test
    fun cutlineWarningsAreUnderlinedAndSpoken() {
        val cutlines = WeeklyCutlines(dailyGunsmokeScore = 40_000, weeklyLoginDays = 5)
        val table = WeeklyTable(base, isGunsmokeWeek = true, zone = ZoneOffset.UTC, cutlines = cutlines)
        val row = table.MemberRow()
        row.bind(member(), rankValue = 1, edits = emptyMap()) {}
        val below = base.getString(R.string.below_cutline)

        val day = cells(row.view).first()
        val score = base.getString(R.string.point_short) + " " + base.getString(R.string.at_least, "31635") + ", " + below
        assertTrue(day.contentDescription.toString(), day.contentDescription.contains(score))
        val caption = day.getChildAt(1) as TextView
        assertTrue(underlined(caption.text as Spanned, "≥31635"))
        assertFalse(underlined(caption.text as Spanned, "≥2"))

        val total = cells(row.view).last()
        val login = base.getString(R.string.login_short) + " 3, " + below
        assertTrue(total.contentDescription.toString(), total.contentDescription.contains(login))
        val activity = total.getChildAt(2) as TextView
        assertTrue(underlined(activity.text as Spanned, "3"))
    }

    @Test
    fun evidenceValuesSpeakLikeTheCell() {
        val no = CellSpeech.mark(base, CellDisplay.Mark("×", R.color.failure_mark, R.string.no))

        assertEquals(base.getString(R.string.no), no)
        assertFalse(no.contains("×"))
        assertEquals(base.getString(R.string.at_least, "90"), CellSpeech.value(base, "≥90"))
        assertEquals(base.getString(R.string.evidence_unknown_short), CellSpeech.value(base, "?"))
    }

    private fun member(): WeeklyReportBuilder.MemberRow {
        val days = (0L until 7L).map { offset ->
            WeeklyReportBuilder.DayCell(
                gameDay = week.plusDays(offset),
                meritDelta = 90,
                scoreDelta = 31_635,
                inference = null,
                evidence = DailyEvidence.PARTIAL_DAY,
                isGunsmokeWeek = true,
                solvedAttempts = 2,
                solvedScoreCertainty = MetricCertainty.LOWER_BOUND,
                solvedAttemptsCertainty = MetricCertainty.LOWER_BOUND,
            )
        }
        return WeeklyReportBuilder.MemberRow(
            uid = 123_456_789,
            name = "Member",
            days = days,
            totalMerit = 630,
            totalScore = 221_445,
            isGunsmokeWeek = true,
            resolvedGunsmokeTotals = WeeklyReportBuilder.ResolvedGunsmokeTotals(
                merit = 630,
                meritCertainty = MetricCertainty.EXACT,
                attempts = 14,
                attemptsCertainty = MetricCertainty.EXACT,
                loginDays = 3,
                loginDaysCertainty = MetricCertainty.EXACT,
                patrolDays = 3,
                patrolDaysCertainty = MetricCertainty.EXACT,
            ),
        )
    }

    private fun scaled(fontScale: Float): Context =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { this.fontScale = fontScale })

    private fun measure(view: View, context: Context, height: Int) {
        val width = (411 * context.resources.displayMetrics.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    /** The daily cells and the weekly total, in order, from one grid row. */
    private fun cells(row: View): List<ViewGroup> {
        val scroller = (row as ViewGroup).getChildAt(1) as HorizontalScrollView
        val strip = scroller.getChildAt(0) as ViewGroup
        return (0 until strip.childCount).map { strip.getChildAt(it) as ViewGroup }
    }

    private fun underlined(text: Spanned, value: String): Boolean {
        val start = text.toString().indexOf(value)
        return start >= 0 && text.getSpans(start, start + value.length, UnderlineSpan::class.java).any { span ->
            text.getSpanStart(span) <= start && text.getSpanEnd(span) >= start + value.length
        }
    }
}
