package dev.gf2log.app.settings

import android.content.Context
import android.content.SharedPreferences
import dev.gf2log.app.management.PlatoonProfileIdentity

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
    private val storageId: String,
) {
    private val appContext = context.applicationContext
    private val scoped = ScopedWeeklyCutlines(
        appContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE),
        storageId,
    )

    fun read(): WeeklyCutlines = if (isLegacy) {
        UserSettingsPreferences.weeklyCutlines(appContext)
    } else {
        scoped.read()
    }

    fun write(cutlines: WeeklyCutlines) {
        if (isLegacy) {
            UserSettingsPreferences.setWeeklyCutlines(appContext, cutlines)
            return
        }
        check(scoped.write(cutlines)) {
            "Unable to persist weekly cutlines"
        }
    }

    fun clear() {
        if (isLegacy) {
            write(WeeklyCutlines())
            return
        }
        check(scoped.write(WeeklyCutlines())) {
            "Unable to clear weekly cutlines"
        }
    }

    private val isLegacy: Boolean
        get() = storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID

    private companion object {
        const val PROFILE_PREFERENCES = "platoon_weekly_cutlines"
    }
}

/**
 * One Platoon's cutlines inside the shared per-Platoon file. A failed commit puts the previous
 * values back, so the tables never highlight against a cutline the screen reported as unsaved.
 */
internal class ScopedWeeklyCutlines(
    private val preferences: SharedPreferences,
    private val storageId: String,
) {
    fun read(): WeeklyCutlines = preferences.readWeeklyCutlines(::key)

    fun write(cutlines: WeeklyCutlines): Boolean =
        preferences.commitOrRevert(WeeklyCutlineField.entries.map(::key)) { putWeeklyCutlines(cutlines, ::key) }

    private fun key(field: WeeklyCutlineField) = "$storageId.${field.scopedKey}"
}
