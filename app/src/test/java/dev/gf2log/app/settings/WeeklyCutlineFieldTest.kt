package dev.gf2log.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyCutlineFieldTest {
    @Test
    fun persistedKeySpellingsNeverChange() {
        assertEquals(
            listOf(
                "cutline.dailyMerit",
                "cutline.dailyGunsmokeScore",
                "cutline.dailyGunsmokeAttempts",
                "cutline.weeklyMerit",
                "cutline.weeklyGunsmokeScore",
                "cutline.weeklyGunsmokeAttempts",
                "cutline.weeklyLoginDays",
                "cutline.weeklyPatrolDays",
            ),
            WeeklyCutlineField.entries.map(WeeklyCutlineField::backupKey),
        )
        assertEquals(
            listOf(
                "cutline_daily_merit",
                "cutline_daily_gunsmoke_score",
                "cutline_daily_gunsmoke_attempts",
                "cutline_weekly_merit",
                "cutline_weekly_gunsmoke_score",
                "cutline_weekly_gunsmoke_attempts",
                "cutline_weekly_login_days",
                "cutline_weekly_patrol_days",
            ),
            WeeklyCutlineField.entries.map(WeeklyCutlineField::settingsKey),
        )
        assertEquals(
            listOf(
                "daily_merit",
                "daily_score",
                "daily_attempts",
                "weekly_merit",
                "weekly_score",
                "weekly_attempts",
                "weekly_login",
                "weekly_patrol",
            ),
            WeeklyCutlineField.entries.map(WeeklyCutlineField::scopedKey),
        )
        assertEquals(
            listOf(false, false, true, false, false, true, true, true),
            WeeklyCutlineField.entries.map(WeeklyCutlineField::storedAsInt),
        )
    }

    @Test
    fun everyFieldRoundTripsThroughTheTable() {
        val cutlines = WeeklyCutlines(
            dailyMerit = 90,
            dailyGunsmokeScore = 10_000,
            dailyGunsmokeAttempts = 3,
            weeklyMerit = 630,
            weeklyGunsmokeScore = 70_000,
            weeklyGunsmokeAttempts = 21,
            weeklyLoginDays = 7,
            weeklyPatrolDays = 5,
        )

        assertEquals(cutlines, WeeklyCutlineField.cutlinesOf { it.valueIn(cutlines) })
        assertEquals(WeeklyCutlines(), WeeklyCutlineField.cutlinesOf { null })
    }
}
