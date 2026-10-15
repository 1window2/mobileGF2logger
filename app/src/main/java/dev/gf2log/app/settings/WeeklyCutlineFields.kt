package dev.gf2log.app.settings

import android.content.SharedPreferences

/**
 * One weekly cutline and every persisted spelling of it. The backup, unified-settings, and
 * per-Platoon keys predate this table and must never change, and each field keeps the
 * SharedPreferences value type it was first written with.
 */
internal enum class WeeklyCutlineField(
    val backupKey: String,
    val settingsKey: String,
    val scopedKey: String,
    val storedAsInt: Boolean,
    val range: LongRange,
) {
    DAILY_MERIT("cutline.dailyMerit", "cutline_daily_merit", "daily_merit", false, 0..Long.MAX_VALUE),
    DAILY_SCORE(
        "cutline.dailyGunsmokeScore",
        "cutline_daily_gunsmoke_score",
        "daily_score",
        false,
        0..Long.MAX_VALUE,
    ),
    DAILY_ATTEMPTS(
        "cutline.dailyGunsmokeAttempts",
        "cutline_daily_gunsmoke_attempts",
        "daily_attempts",
        true,
        0L..3L,
    ),
    WEEKLY_MERIT("cutline.weeklyMerit", "cutline_weekly_merit", "weekly_merit", false, 0..Long.MAX_VALUE),
    WEEKLY_SCORE(
        "cutline.weeklyGunsmokeScore",
        "cutline_weekly_gunsmoke_score",
        "weekly_score",
        false,
        0..Long.MAX_VALUE,
    ),
    WEEKLY_ATTEMPTS(
        "cutline.weeklyGunsmokeAttempts",
        "cutline_weekly_gunsmoke_attempts",
        "weekly_attempts",
        true,
        0L..21L,
    ),
    WEEKLY_LOGIN_DAYS("cutline.weeklyLoginDays", "cutline_weekly_login_days", "weekly_login", true, 0L..7L),
    WEEKLY_PATROL_DAYS(
        "cutline.weeklyPatrolDays",
        "cutline_weekly_patrol_days",
        "weekly_patrol",
        true,
        0L..7L,
    ),
    ;

    fun valueIn(cutlines: WeeklyCutlines): Long? = when (this) {
        DAILY_MERIT -> cutlines.dailyMerit
        DAILY_SCORE -> cutlines.dailyGunsmokeScore
        DAILY_ATTEMPTS -> cutlines.dailyGunsmokeAttempts?.toLong()
        WEEKLY_MERIT -> cutlines.weeklyMerit
        WEEKLY_SCORE -> cutlines.weeklyGunsmokeScore
        WEEKLY_ATTEMPTS -> cutlines.weeklyGunsmokeAttempts?.toLong()
        WEEKLY_LOGIN_DAYS -> cutlines.weeklyLoginDays?.toLong()
        WEEKLY_PATROL_DAYS -> cutlines.weeklyPatrolDays?.toLong()
    }

    companion object {
        fun cutlinesOf(value: (WeeklyCutlineField) -> Long?): WeeklyCutlines = WeeklyCutlines(
            dailyMerit = value(DAILY_MERIT),
            dailyGunsmokeScore = value(DAILY_SCORE),
            dailyGunsmokeAttempts = value(DAILY_ATTEMPTS)?.toInt(),
            weeklyMerit = value(WEEKLY_MERIT),
            weeklyGunsmokeScore = value(WEEKLY_SCORE),
            weeklyGunsmokeAttempts = value(WEEKLY_ATTEMPTS)?.toInt(),
            weeklyLoginDays = value(WEEKLY_LOGIN_DAYS)?.toInt(),
            weeklyPatrolDays = value(WEEKLY_PATROL_DAYS)?.toInt(),
        )
    }
}

internal fun SharedPreferences.readWeeklyCutlines(key: (WeeklyCutlineField) -> String): WeeklyCutlines =
    WeeklyCutlineField.cutlinesOf { field ->
        val stored = key(field)
        when {
            !contains(stored) -> null
            field.storedAsInt -> getInt(stored, 0).toLong()
            else -> getLong(stored, 0L)
        }
    }

internal fun SharedPreferences.Editor.putWeeklyCutlines(
    cutlines: WeeklyCutlines,
    key: (WeeklyCutlineField) -> String,
): SharedPreferences.Editor = apply {
    WeeklyCutlineField.entries.forEach { field ->
        val stored = key(field)
        val value = field.valueIn(cutlines)
        when {
            value == null -> remove(stored)
            field.storedAsInt -> putInt(stored, value.toInt())
            else -> putLong(stored, value)
        }
    }
}
