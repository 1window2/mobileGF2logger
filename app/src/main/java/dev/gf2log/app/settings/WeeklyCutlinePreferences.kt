package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.app.management.PlatoonProfileIdentity
import dev.gf2log.app.management.PlatoonProfileRegistry

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

class WeeklyCutlinePreferences(
    context: Context,
    private val storageId: String = PlatoonProfileRegistry(context).activeScope().storageId,
) {
    private val appContext = context.applicationContext
    private val scoped = appContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)

    fun read(): WeeklyCutlines = if (isLegacy) {
        UserSettingsPreferences.weeklyCutlines(appContext)
    } else {
        WeeklyCutlines(
            dailyMerit = scoped.optionalLong(key(DAILY_MERIT)),
            dailyGunsmokeScore = scoped.optionalLong(key(DAILY_SCORE)),
            dailyGunsmokeAttempts = scoped.optionalInt(key(DAILY_ATTEMPTS)),
            weeklyMerit = scoped.optionalLong(key(WEEKLY_MERIT)),
            weeklyGunsmokeScore = scoped.optionalLong(key(WEEKLY_SCORE)),
            weeklyGunsmokeAttempts = scoped.optionalInt(key(WEEKLY_ATTEMPTS)),
            weeklyLoginDays = scoped.optionalInt(key(WEEKLY_LOGIN)),
            weeklyPatrolDays = scoped.optionalInt(key(WEEKLY_PATROL)),
        )
    }

    fun write(cutlines: WeeklyCutlines) {
        if (isLegacy) {
            UserSettingsPreferences.setWeeklyCutlines(appContext, cutlines)
            return
        }
        val editor = scoped.edit()
        editor.putOptionalLong(key(DAILY_MERIT), cutlines.dailyMerit)
        editor.putOptionalLong(key(DAILY_SCORE), cutlines.dailyGunsmokeScore)
        editor.putOptionalInt(key(DAILY_ATTEMPTS), cutlines.dailyGunsmokeAttempts)
        editor.putOptionalLong(key(WEEKLY_MERIT), cutlines.weeklyMerit)
        editor.putOptionalLong(key(WEEKLY_SCORE), cutlines.weeklyGunsmokeScore)
        editor.putOptionalInt(key(WEEKLY_ATTEMPTS), cutlines.weeklyGunsmokeAttempts)
        editor.putOptionalInt(key(WEEKLY_LOGIN), cutlines.weeklyLoginDays)
        editor.putOptionalInt(key(WEEKLY_PATROL), cutlines.weeklyPatrolDays)
        check(editor.commit()) { "Unable to persist weekly cutlines" }
    }

    fun clear() {
        if (isLegacy) {
            write(WeeklyCutlines())
            return
        }
        val editor = scoped.edit()
        listOf(
            DAILY_MERIT,
            DAILY_SCORE,
            DAILY_ATTEMPTS,
            WEEKLY_MERIT,
            WEEKLY_SCORE,
            WEEKLY_ATTEMPTS,
            WEEKLY_LOGIN,
            WEEKLY_PATROL,
        ).forEach { editor.remove(key(it)) }
        check(editor.commit()) { "Unable to clear weekly cutlines" }
    }

    private val isLegacy: Boolean
        get() = storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID

    private fun key(name: String) = "$storageId.$name"

    private fun android.content.SharedPreferences.optionalLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.optionalInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun android.content.SharedPreferences.Editor.putOptionalInt(key: String, value: Int?) {
        if (value == null) remove(key) else putInt(key, value)
    }

    private companion object {
        const val PROFILE_PREFERENCES = "platoon_weekly_cutlines"
        const val DAILY_MERIT = "daily_merit"
        const val DAILY_SCORE = "daily_score"
        const val DAILY_ATTEMPTS = "daily_attempts"
        const val WEEKLY_MERIT = "weekly_merit"
        const val WEEKLY_SCORE = "weekly_score"
        const val WEEKLY_ATTEMPTS = "weekly_attempts"
        const val WEEKLY_LOGIN = "weekly_login"
        const val WEEKLY_PATROL = "weekly_patrol"
    }
}
