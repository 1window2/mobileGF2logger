package dev.gf2log.app.settings

import android.content.Context

data class WeeklyCutlines(
    val dailyMerit: Long? = null,
    val dailyGunsmokeScore: Long? = null,
    val dailyGunsmokeAttempts: Int? = null,
    val weeklyMerit: Long? = null,
    val weeklyGunsmokeScore: Long? = null,
    val weeklyGunsmokeAttempts: Int? = null,
    val weeklyLoginDays: Int? = null,
    val weeklyPatrolDays: Int? = null,
) {
    fun belowDailyMerit(value: Long): Boolean =
        dailyMerit?.let { value < it } == true

    fun belowDailyScore(value: Long): Boolean =
        dailyGunsmokeScore?.let { value < it } == true

    fun belowDailyAttempts(value: Int): Boolean =
        dailyGunsmokeAttempts?.let { value < it } == true

    fun belowWeeklyMerit(value: Long): Boolean =
        weeklyMerit?.let { value < it } == true

    fun belowWeeklyScore(value: Long): Boolean =
        weeklyGunsmokeScore?.let { value < it } == true

    fun belowWeeklyAttempts(value: Int): Boolean =
        weeklyGunsmokeAttempts?.let { value < it } == true

    fun belowWeeklyLoginDays(value: Int): Boolean =
        weeklyLoginDays?.let { value < it } == true

    fun belowWeeklyPatrolDays(value: Int): Boolean =
        weeklyPatrolDays?.let { value < it } == true
}

class WeeklyCutlinePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun read(): WeeklyCutlines {
        if (!hasIndependentCutlines()) return readLegacyCutlines()
        return WeeklyCutlines(
            dailyMerit = optionalLong(KEY_DAILY_MERIT),
            dailyGunsmokeScore = optionalLong(KEY_DAILY_SCORE),
            dailyGunsmokeAttempts = optionalInt(KEY_DAILY_ATTEMPTS),
            weeklyMerit = optionalLong(KEY_WEEKLY_MERIT),
            weeklyGunsmokeScore = optionalLong(KEY_WEEKLY_SCORE),
            weeklyGunsmokeAttempts = optionalInt(KEY_WEEKLY_ATTEMPTS),
            weeklyLoginDays = optionalInt(KEY_LOGIN_DAYS),
            weeklyPatrolDays = optionalInt(KEY_PATROL_DAYS),
        )
    }

    fun write(cutlines: WeeklyCutlines) {
        preferences.edit()
            .putOptionalLong(KEY_DAILY_MERIT, cutlines.dailyMerit)
            .putOptionalLong(KEY_DAILY_SCORE, cutlines.dailyGunsmokeScore)
            .putOptionalInt(KEY_DAILY_ATTEMPTS, cutlines.dailyGunsmokeAttempts)
            .putOptionalLong(KEY_WEEKLY_MERIT, cutlines.weeklyMerit)
            .putOptionalLong(KEY_WEEKLY_SCORE, cutlines.weeklyGunsmokeScore)
            .putOptionalInt(KEY_WEEKLY_ATTEMPTS, cutlines.weeklyGunsmokeAttempts)
            .putOptionalInt(KEY_LOGIN_DAYS, cutlines.weeklyLoginDays)
            .putOptionalInt(KEY_PATROL_DAYS, cutlines.weeklyPatrolDays)
            .remove(KEY_LEGACY_PERIOD)
            .remove(KEY_LEGACY_MERIT)
            .remove(KEY_LEGACY_SCORE)
            .remove(KEY_LEGACY_ATTEMPTS)
            .apply()
    }

    private fun hasIndependentCutlines(): Boolean = listOf(
        KEY_DAILY_MERIT,
        KEY_DAILY_SCORE,
        KEY_DAILY_ATTEMPTS,
        KEY_WEEKLY_MERIT,
        KEY_WEEKLY_SCORE,
        KEY_WEEKLY_ATTEMPTS,
    ).any(preferences::contains)

    private fun readLegacyCutlines(): WeeklyCutlines {
        val daily = preferences.getString(KEY_LEGACY_PERIOD, "DAILY") != "WEEKLY"
        val merit = optionalLong(KEY_LEGACY_MERIT)
        val score = optionalLong(KEY_LEGACY_SCORE)
        val attempts = optionalInt(KEY_LEGACY_ATTEMPTS)
        return WeeklyCutlines(
            dailyMerit = merit.takeIf { daily },
            dailyGunsmokeScore = score.takeIf { daily },
            dailyGunsmokeAttempts = attempts.takeIf { daily },
            weeklyMerit = merit.takeUnless { daily },
            weeklyGunsmokeScore = score.takeUnless { daily },
            weeklyGunsmokeAttempts = attempts.takeUnless { daily },
            weeklyLoginDays = optionalInt(KEY_LOGIN_DAYS),
            weeklyPatrolDays = optionalInt(KEY_PATROL_DAYS),
        )
    }

    private fun optionalLong(key: String): Long? =
        if (preferences.contains(key)) preferences.getLong(key, 0) else null

    private fun optionalInt(key: String): Int? =
        if (preferences.contains(key)) preferences.getInt(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putOptionalLong(
        key: String,
        value: Long?,
    ) = if (value == null) remove(key) else putLong(key, value)

    private fun android.content.SharedPreferences.Editor.putOptionalInt(
        key: String,
        value: Int?,
    ) = if (value == null) remove(key) else putInt(key, value)

    private companion object {
        const val PREFERENCES = "weekly_cutlines"
        const val KEY_DAILY_MERIT = "daily_merit"
        const val KEY_DAILY_SCORE = "daily_gunsmoke_score"
        const val KEY_DAILY_ATTEMPTS = "daily_gunsmoke_attempts"
        const val KEY_WEEKLY_MERIT = "weekly_merit"
        const val KEY_WEEKLY_SCORE = "weekly_gunsmoke_score"
        const val KEY_WEEKLY_ATTEMPTS = "weekly_gunsmoke_attempts"
        const val KEY_LOGIN_DAYS = "weekly_login_days"
        const val KEY_PATROL_DAYS = "weekly_patrol_days"
        const val KEY_LEGACY_PERIOD = "period"
        const val KEY_LEGACY_MERIT = "merit"
        const val KEY_LEGACY_SCORE = "gunsmoke_score"
        const val KEY_LEGACY_ATTEMPTS = "gunsmoke_attempts"
    }
}
