package dev.gf2log.app.settings

import android.content.Context
import android.content.SharedPreferences
import dev.gf2log.app.LanguagePreferences
import dev.gf2log.app.TargetPackagePreferences
import dev.gf2log.protocol.PayloadCatalog

internal object UserSettingsPreferences {
    private const val PREFERENCES = "user_settings"
    private const val SCHEMA_VERSION = 1
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_DETAILED_NOTIFICATIONS = "detailed_notifications"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_MEMBER_ORDER = "member_order"
    private const val KEY_DAILY_MERIT = "cutline_daily_merit"
    private const val KEY_DAILY_SCORE = "cutline_daily_gunsmoke_score"
    private const val KEY_DAILY_ATTEMPTS = "cutline_daily_gunsmoke_attempts"
    private const val KEY_WEEKLY_MERIT = "cutline_weekly_merit"
    private const val KEY_WEEKLY_SCORE = "cutline_weekly_gunsmoke_score"
    private const val KEY_WEEKLY_ATTEMPTS = "cutline_weekly_gunsmoke_attempts"
    private const val KEY_WEEKLY_LOGIN_DAYS = "cutline_weekly_login_days"
    private const val KEY_WEEKLY_PATROL_DAYS = "cutline_weekly_patrol_days"

    private val lock = Any()

    fun read(context: Context): AppBackupSettings = synchronized(lock) {
        readLocked(preferencesLocked(context.applicationContext))
    }

    fun replace(context: Context, settings: AppBackupSettings) = synchronized(lock) {
        AppBackupSettingsCodec.encode(settings)
        val preferences = preferencesLocked(context.applicationContext)
        check(writeSettings(preferences.edit().clear(), settings).commit()) {
            "Unable to replace app settings"
        }
    }

    fun language(context: Context): String = synchronized(lock) {
        preferencesLocked(context.applicationContext)
            .getString(KEY_LANGUAGE, LanguagePreferences.DEFAULT_LANGUAGE)
            .orEmpty()
    }

    fun setLanguage(context: Context, language: String) = edit(context) {
        putString(KEY_LANGUAGE, language)
    }

    fun detailedNotifications(context: Context): Boolean = synchronized(lock) {
        preferencesLocked(context.applicationContext)
            .getBoolean(KEY_DETAILED_NOTIFICATIONS, true)
    }

    fun setDetailedNotifications(context: Context, enabled: Boolean) = edit(context) {
        putBoolean(KEY_DETAILED_NOTIFICATIONS, enabled)
    }

    fun payloadHistoryEnabled(context: Context, payloadType: Int): Boolean = synchronized(lock) {
        val category = PayloadCatalog.find(payloadType) ?: return@synchronized false
        category.isRequired || preferencesLocked(context.applicationContext)
            .getBoolean(payloadKey(payloadType), false)
    }

    fun setPayloadHistoryEnabled(context: Context, payloadType: Int, enabled: Boolean) =
        edit(context) { putBoolean(payloadKey(payloadType), enabled) }

    fun memberOrder(context: Context): List<Long> = synchronized(lock) {
        parseMemberOrder(
            preferencesLocked(context.applicationContext).getString(KEY_MEMBER_ORDER, null),
        )
    }

    fun setMemberOrder(context: Context, uids: List<Long>) = edit(context) {
        putString(KEY_MEMBER_ORDER, uids.distinct().joinToString(","))
    }

    fun clearMemberOrder(context: Context) = edit(context) { remove(KEY_MEMBER_ORDER) }

    fun weeklyCutlines(context: Context): WeeklyCutlines = synchronized(lock) {
        readCutlines(preferencesLocked(context.applicationContext))
    }

    fun setWeeklyCutlines(context: Context, cutlines: WeeklyCutlines) = edit(context) {
        putCutlines(cutlines)
    }

    fun targetPackage(context: Context): String = synchronized(lock) {
        preferencesLocked(context.applicationContext)
            .getString(KEY_TARGET_PACKAGE, TargetPackagePreferences.DEFAULT_TARGET_PACKAGE)
            ?.takeIf(String::isNotBlank)
            ?: TargetPackagePreferences.DEFAULT_TARGET_PACKAGE
    }

    fun setTargetPackage(context: Context, targetPackage: String) = edit(context) {
        putString(KEY_TARGET_PACKAGE, targetPackage)
    }

    private fun edit(
        context: Context,
        mutation: SharedPreferences.Editor.() -> SharedPreferences.Editor,
    ) = synchronized(lock) {
        preferencesLocked(context.applicationContext).edit().mutation().apply()
    }

    private fun preferencesLocked(context: Context): SharedPreferences {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getInt(KEY_SCHEMA_VERSION, 0) == SCHEMA_VERSION) return preferences
        val migrated = readLegacy(context)
        check(writeSettings(preferences.edit().clear(), migrated).commit()) {
            "Unable to migrate app settings"
        }
        return preferences
    }

    private fun readLocked(preferences: SharedPreferences) = AppBackupSettings(
        language = preferences.getString(KEY_LANGUAGE, LanguagePreferences.DEFAULT_LANGUAGE)
            .orEmpty(),
        detailedNotifications = preferences.getBoolean(KEY_DETAILED_NOTIFICATIONS, true),
        targetPackage = preferences.getString(
            KEY_TARGET_PACKAGE,
            TargetPackagePreferences.DEFAULT_TARGET_PACKAGE,
        ).orEmpty(),
        payloadHistory = PayloadCatalog.categories.associate { category ->
            category.payloadType to
                (category.isRequired || preferences.getBoolean(payloadKey(category.payloadType), false))
        },
        memberOrder = parseMemberOrder(preferences.getString(KEY_MEMBER_ORDER, null)),
        weeklyCutlines = readCutlines(preferences),
    )

    private fun readLegacy(context: Context): AppBackupSettings {
        val language = context.getSharedPreferences("display_settings", Context.MODE_PRIVATE)
            .safeString("language")
            ?.takeIf { it in setOf(LanguagePreferences.DEFAULT_LANGUAGE, LanguagePreferences.KOREAN) }
            ?: LanguagePreferences.DEFAULT_LANGUAGE
        val detailedNotifications = context
            .getSharedPreferences("capture_preferences", Context.MODE_PRIVATE)
            .safeBoolean("detailed_notifications") ?: true
        val payloadPreferences = context.getSharedPreferences(
            "payload_history_options",
            Context.MODE_PRIVATE,
        )
        val memberOrder = parseMemberOrder(
            context.getSharedPreferences("weekly_member_order", Context.MODE_PRIVATE)
                .safeString("uids"),
        ).filter { it > 0L }
        val legacyCutlines = readLegacyCutlines(
            context.getSharedPreferences("weekly_cutlines", Context.MODE_PRIVATE),
        )
        val targetPackage = context.getSharedPreferences(
            TargetPackagePreferences.LEGACY_PREFERENCES,
            Context.MODE_PRIVATE,
        ).safeString(TargetPackagePreferences.KEY_TARGET_PACKAGE)
            ?.trim()
            ?.takeIf { TARGET_PACKAGE.matches(it) }
            ?: TargetPackagePreferences.DEFAULT_TARGET_PACKAGE
        return AppBackupSettings(
            language = language,
            detailedNotifications = detailedNotifications,
            targetPackage = targetPackage,
            payloadHistory = PayloadCatalog.categories.associate { category ->
                category.payloadType to
                    (category.isRequired || payloadPreferences.safeBoolean(payloadKey(category.payloadType)) == true)
            },
            memberOrder = memberOrder.distinct(),
            weeklyCutlines = legacyCutlines,
        )
    }

    private fun readLegacyCutlines(preferences: SharedPreferences): WeeklyCutlines {
        val hasIndependentCutlines = listOf(
            "daily_merit",
            "daily_gunsmoke_score",
            "daily_gunsmoke_attempts",
            "weekly_merit",
            "weekly_gunsmoke_score",
            "weekly_gunsmoke_attempts",
        ).any(preferences::contains)
        if (hasIndependentCutlines) {
            return WeeklyCutlines(
                dailyMerit = preferences.safeLong("daily_merit")?.takeIf { it >= 0 },
                dailyGunsmokeScore = preferences.safeLong("daily_gunsmoke_score")
                    ?.takeIf { it >= 0 },
                dailyGunsmokeAttempts = preferences.safeInt("daily_gunsmoke_attempts")
                    ?.takeIf { it in 0..3 },
                weeklyMerit = preferences.safeLong("weekly_merit")?.takeIf { it >= 0 },
                weeklyGunsmokeScore = preferences.safeLong("weekly_gunsmoke_score")
                    ?.takeIf { it >= 0 },
                weeklyGunsmokeAttempts = preferences.safeInt("weekly_gunsmoke_attempts")
                    ?.takeIf { it in 0..21 },
                weeklyLoginDays = preferences.safeInt("weekly_login_days")
                    ?.takeIf { it in 0..7 },
                weeklyPatrolDays = preferences.safeInt("weekly_patrol_days")
                    ?.takeIf { it in 0..7 },
            )
        }
        val daily = preferences.safeString("period") != "WEEKLY"
        val merit = preferences.safeLong("merit")?.takeIf { it >= 0 }
        val score = preferences.safeLong("gunsmoke_score")?.takeIf { it >= 0 }
        val attempts = preferences.safeInt("gunsmoke_attempts")?.takeIf { it in 0..21 }
        return WeeklyCutlines(
            dailyMerit = merit.takeIf { daily },
            dailyGunsmokeScore = score.takeIf { daily },
            dailyGunsmokeAttempts = attempts?.takeIf { daily && it <= 3 },
            weeklyMerit = merit.takeUnless { daily },
            weeklyGunsmokeScore = score.takeUnless { daily },
            weeklyGunsmokeAttempts = attempts.takeUnless { daily },
            weeklyLoginDays = preferences.safeInt("weekly_login_days")
                ?.takeIf { it in 0..7 },
            weeklyPatrolDays = preferences.safeInt("weekly_patrol_days")
                ?.takeIf { it in 0..7 },
        )
    }

    private fun readCutlines(preferences: SharedPreferences) = WeeklyCutlines(
        dailyMerit = preferences.optionalLong(KEY_DAILY_MERIT),
        dailyGunsmokeScore = preferences.optionalLong(KEY_DAILY_SCORE),
        dailyGunsmokeAttempts = preferences.optionalInt(KEY_DAILY_ATTEMPTS),
        weeklyMerit = preferences.optionalLong(KEY_WEEKLY_MERIT),
        weeklyGunsmokeScore = preferences.optionalLong(KEY_WEEKLY_SCORE),
        weeklyGunsmokeAttempts = preferences.optionalInt(KEY_WEEKLY_ATTEMPTS),
        weeklyLoginDays = preferences.optionalInt(KEY_WEEKLY_LOGIN_DAYS),
        weeklyPatrolDays = preferences.optionalInt(KEY_WEEKLY_PATROL_DAYS),
    )

    private fun writeSettings(
        editor: SharedPreferences.Editor,
        settings: AppBackupSettings,
    ): SharedPreferences.Editor = editor
        .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        .putString(KEY_LANGUAGE, settings.language)
        .putBoolean(KEY_DETAILED_NOTIFICATIONS, settings.detailedNotifications)
        .putString(KEY_TARGET_PACKAGE, settings.targetPackage)
        .putString(KEY_MEMBER_ORDER, settings.memberOrder.joinToString(","))
        .also { target ->
            settings.payloadHistory.forEach { (payloadType, enabled) ->
                target.putBoolean(payloadKey(payloadType), enabled)
            }
        }
        .putCutlines(settings.weeklyCutlines)

    private fun SharedPreferences.Editor.putCutlines(cutlines: WeeklyCutlines) =
        putOptionalLong(KEY_DAILY_MERIT, cutlines.dailyMerit)
            .putOptionalLong(KEY_DAILY_SCORE, cutlines.dailyGunsmokeScore)
            .putOptionalInt(KEY_DAILY_ATTEMPTS, cutlines.dailyGunsmokeAttempts)
            .putOptionalLong(KEY_WEEKLY_MERIT, cutlines.weeklyMerit)
            .putOptionalLong(KEY_WEEKLY_SCORE, cutlines.weeklyGunsmokeScore)
            .putOptionalInt(KEY_WEEKLY_ATTEMPTS, cutlines.weeklyGunsmokeAttempts)
            .putOptionalInt(KEY_WEEKLY_LOGIN_DAYS, cutlines.weeklyLoginDays)
            .putOptionalInt(KEY_WEEKLY_PATROL_DAYS, cutlines.weeklyPatrolDays)

    private fun SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) =
        if (value == null) remove(key) else putLong(key, value)

    private fun SharedPreferences.Editor.putOptionalInt(key: String, value: Int?) =
        if (value == null) remove(key) else putInt(key, value)

    private fun SharedPreferences.optionalLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun SharedPreferences.optionalInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun SharedPreferences.safeString(key: String): String? =
        runCatching { getString(key, null) }.getOrNull()

    private fun SharedPreferences.safeBoolean(key: String): Boolean? =
        runCatching { getBoolean(key, false) }.getOrNull().takeIf { contains(key) }

    private fun SharedPreferences.safeLong(key: String): Long? =
        runCatching { getLong(key, 0L) }.getOrNull().takeIf { contains(key) }

    private fun SharedPreferences.safeInt(key: String): Int? =
        runCatching { getInt(key, 0) }.getOrNull().takeIf { contains(key) }

    private fun parseMemberOrder(value: String?): List<Long> = value.orEmpty()
        .split(',')
        .mapNotNull(String::toLongOrNull)
        .distinct()

    private fun payloadKey(payloadType: Int) = "payload_$payloadType"

    private val TARGET_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}
