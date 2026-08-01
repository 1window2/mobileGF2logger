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
    private val appContext = context.applicationContext

    fun read(): WeeklyCutlines = UserSettingsPreferences.weeklyCutlines(appContext)

    fun write(cutlines: WeeklyCutlines) {
        UserSettingsPreferences.setWeeklyCutlines(appContext, cutlines)
    }
}
