package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyHistoryWorkPolicyTest {
    private val utc = ZoneOffset.UTC
    private val closingWeek = LocalDate.of(2026, 7, 26)
    private val openingWeek = LocalDate.of(2026, 8, 2)
    private val laterWeek = LocalDate.of(2026, 8, 9)
    private val listed = listOf(closingWeek, openingWeek, laterWeek)

    @Test
    fun `weekly history work is capped after distinct week mapping`() {
        val instants = generateSequence(Instant.parse("2020-01-06T05:00:00Z")) {
            it.plusSeconds(7 * 24 * 60 * 60L)
        }.take(WeeklyHistoryWorkPolicy.MAX_CHANGED_WEEKS_PER_INGEST + 50)

        val periods = WeeklyHistoryWorkPolicy.affectedPeriodStarts(instants, utc, emptyList()) { null }

        assertEquals(WeeklyHistoryWorkPolicy.MAX_CHANGED_WEEKS_PER_INGEST, periods.size)
        assertEquals(periods.size, periods.distinct().size)
    }

    @Test
    fun `neighbouring weeks never push the result past the per-ingest bound`() {
        val weeks = (0L until WeeklyHistoryWorkPolicy.MAX_CHANGED_WEEKS_PER_INGEST + 10L)
            .map { openingWeek.plusWeeks(it) }

        val periods = WeeklyHistoryWorkPolicy.affectedPeriodStarts(
            sequenceOf(Instant.parse("2026-08-03T12:00:00Z")),
            utc,
            weeks,
        ) { null }

        assertEquals(WeeklyHistoryWorkPolicy.MAX_CHANGED_WEEKS_PER_INGEST, periods.size)
        assertEquals(openingWeek, periods.first())
    }

    @Test
    fun `a mid-week instant before a later roster affects only its own week`() {
        val periods = affected(
            Instant.parse("2026-07-29T12:00:00Z"),
            nextSnapshot = Instant.parse("2026-07-30T12:00:00Z"),
        )

        assertEquals(listOf(closingWeek), periods)
    }

    @Test
    fun `a roster at exactly the Sunday reset also affects the week it closes`() {
        val periods = affected(
            Instant.parse("2026-08-02T05:00:00Z"),
            nextSnapshot = Instant.parse("2026-08-03T12:00:00Z"),
        )

        assertEquals(listOf(openingWeek, closingWeek), periods)
    }

    @Test
    fun `a roster just before the reset is the next week's baseline`() {
        val periods = affected(
            Instant.parse("2026-08-02T04:55:00Z"),
            nextSnapshot = Instant.parse("2026-08-02T12:00:00Z"),
        )

        assertEquals(listOf(closingWeek, openingWeek), periods)
    }

    @Test
    fun `the newest roster carries into every later listed week`() {
        val periods = affected(Instant.parse("2026-07-31T12:00:00Z"), nextSnapshot = null)

        assertEquals(listOf(closingWeek, openingWeek, laterWeek), periods)
    }

    @Test
    fun `carry-in stops at the first week opened after the next roster`() {
        // A next roster at the opening reset itself is not yet before that week, so it still
        // takes the earlier instant as its carried-in state.
        assertEquals(
            listOf(closingWeek, openingWeek),
            affected(
                Instant.parse("2026-07-31T12:00:00Z"),
                nextSnapshot = Instant.parse("2026-08-02T05:00:00Z"),
            ),
        )
        assertEquals(
            listOf(closingWeek),
            affected(
                Instant.parse("2026-07-31T12:00:00Z"),
                nextSnapshot = Instant.parse("2026-08-02T04:59:59Z"),
            ),
        )
    }

    @Test
    fun `an early calendar Sunday instant affects the next week's Join and Withdraw section`() {
        val seoul = ZoneId.of("Asia/Seoul")
        // 03:00 on Sunday 2026-08-02 in Seoul is still Saturday's game day.
        val instant = Instant.parse("2026-08-01T18:00:00Z")

        val periods = WeeklyHistoryWorkPolicy.affectedPeriodStarts(
            sequenceOf(instant),
            seoul,
            listed,
        ) { Instant.parse("2026-08-01T19:00:00Z") }

        assertEquals(listOf(closingWeek, openingWeek), periods)
    }

    @Test
    fun `neighbouring weeks are refreshed only when the history lists them`() {
        val periods = WeeklyHistoryWorkPolicy.affectedPeriodStarts(
            sequenceOf(Instant.parse("2026-08-02T05:00:00Z")),
            utc,
            listOf(openingWeek),
        ) { null }

        assertEquals(listOf(openingWeek), periods)
    }

    @Test
    fun `the next roster is looked up once after the latest instant of each week`() {
        val lookups = mutableListOf<Instant>()

        WeeklyHistoryWorkPolicy.affectedPeriodStarts(
            sequenceOf(
                Instant.parse("2026-07-29T12:00:00Z"),
                Instant.parse("2026-07-31T12:00:00Z"),
                Instant.parse("2026-07-30T12:00:00Z"),
            ),
            utc,
            listed,
        ) { instant ->
            lookups += instant
            null
        }

        assertEquals(listOf(Instant.parse("2026-07-31T12:00:00Z")), lookups)
    }

    @Test
    fun `a membership edit refreshes still-listed earlier weeks and every week showing it now`() {
        val periods = WeeklyHistoryWorkPolicy.memberEditPeriodStarts(
            edit = WeeklyHistoryWorkPolicy.MemberEdit.MEMBERSHIP,
            showingBefore = setOf(laterWeek, closingWeek),
            listedAfter = { listOf(openingWeek, laterWeek) },
            showingAfter = { listedWeeks ->
                assertEquals(listOf(openingWeek, laterWeek), listedWeeks)
                setOf(openingWeek)
            },
        )

        assertEquals(listOf(openingWeek, laterWeek), periods)
    }

    @Test
    fun `a display-only edit reuses the pre-scan instead of listing and scanning again`() {
        val showing = setOf(laterWeek, closingWeek)

        val periods = WeeklyHistoryWorkPolicy.memberEditPeriodStarts(
            edit = WeeklyHistoryWorkPolicy.MemberEdit.DISPLAY_ONLY,
            showingBefore = showing,
            listedAfter = { error("a name edit cannot change the listed weeks") },
            showingAfter = { error("a name edit cannot change the rosters") },
        )

        assertEquals(listOf(closingWeek, laterWeek), periods)
        assertEquals(
            WeeklyHistoryWorkPolicy.memberEditPeriodStarts(
                edit = WeeklyHistoryWorkPolicy.MemberEdit.MEMBERSHIP,
                showingBefore = showing,
                listedAfter = { listed },
                showingAfter = { showing },
            ),
            periods,
        )
    }

    @Test
    fun `a removal refreshes only the earlier weeks still listed and scans no roster`() {
        val showing = setOf(laterWeek, closingWeek)
        val remaining = listOf(openingWeek, laterWeek)

        val periods = WeeklyHistoryWorkPolicy.memberEditPeriodStarts(
            edit = WeeklyHistoryWorkPolicy.MemberEdit.REMOVAL,
            showingBefore = showing,
            listedAfter = { remaining },
            showingAfter = { error("a removed member is shown nowhere") },
        )

        assertEquals(listOf(laterWeek), periods)
        assertEquals(
            WeeklyHistoryWorkPolicy.memberEditPeriodStarts(
                edit = WeeklyHistoryWorkPolicy.MemberEdit.MEMBERSHIP,
                showingBefore = showing,
                listedAfter = { remaining },
                showingAfter = { emptySet() },
            ),
            periods,
        )
    }

    @Test
    fun `a failed pre-scan refreshes every listed week for every edit`() {
        WeeklyHistoryWorkPolicy.MemberEdit.entries.forEach { edit ->
            assertEquals(
                listed,
                WeeklyHistoryWorkPolicy.memberEditPeriodStarts(
                    edit = edit,
                    showingBefore = null,
                    listedAfter = { listed },
                    showingAfter = { error("no scoped scan without a pre-scan") },
                ),
            )
        }
    }

    private fun affected(instant: Instant, nextSnapshot: Instant?) =
        WeeklyHistoryWorkPolicy.affectedPeriodStarts(sequenceOf(instant), utc, listed) { nextSnapshot }
}
