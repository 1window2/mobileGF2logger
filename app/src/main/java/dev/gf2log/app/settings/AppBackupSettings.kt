package dev.gf2log.app.settings

import dev.gf2log.protocol.PayloadCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

data class AppBackupSettings(
    val language: String,
    val detailedNotifications: Boolean,
    val targetPackage: String,
    val payloadHistory: Map<Int, Boolean>,
    val memberOrder: List<Long>,
    val weeklyCutlines: WeeklyCutlines,
)

object AppBackupSettingsCodec {
    private const val SCHEMA_VERSION = 1
    private const val NONE = "none"
    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_DETAILED_NOTIFICATIONS = "detailedNotifications"
    private const val KEY_TARGET_PACKAGE = "targetPackage"
    private const val KEY_MEMBER_ORDER = "memberOrder"
    private const val KEY_DAILY_MERIT = "cutline.dailyMerit"
    private const val KEY_DAILY_SCORE = "cutline.dailyGunsmokeScore"
    private const val KEY_DAILY_ATTEMPTS = "cutline.dailyGunsmokeAttempts"
    private const val KEY_WEEKLY_MERIT = "cutline.weeklyMerit"
    private const val KEY_WEEKLY_SCORE = "cutline.weeklyGunsmokeScore"
    private const val KEY_WEEKLY_ATTEMPTS = "cutline.weeklyGunsmokeAttempts"
    private const val KEY_WEEKLY_LOGIN_DAYS = "cutline.weeklyLoginDays"
    private const val KEY_WEEKLY_PATROL_DAYS = "cutline.weeklyPatrolDays"

    fun encode(settings: AppBackupSettings): ByteArray {
        validate(settings)
        val properties = Properties().apply {
            setProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION.toString())
            setProperty(KEY_LANGUAGE, settings.language)
            setProperty(KEY_DETAILED_NOTIFICATIONS, settings.detailedNotifications.toString())
            setProperty(KEY_TARGET_PACKAGE, settings.targetPackage)
            setProperty(KEY_MEMBER_ORDER, settings.memberOrder.joinToString(","))
            setProperty(KEY_DAILY_MERIT, settings.weeklyCutlines.dailyMerit.encoded())
            setProperty(KEY_DAILY_SCORE, settings.weeklyCutlines.dailyGunsmokeScore.encoded())
            setProperty(KEY_DAILY_ATTEMPTS, settings.weeklyCutlines.dailyGunsmokeAttempts.encoded())
            setProperty(KEY_WEEKLY_MERIT, settings.weeklyCutlines.weeklyMerit.encoded())
            setProperty(KEY_WEEKLY_SCORE, settings.weeklyCutlines.weeklyGunsmokeScore.encoded())
            setProperty(KEY_WEEKLY_ATTEMPTS, settings.weeklyCutlines.weeklyGunsmokeAttempts.encoded())
            setProperty(KEY_WEEKLY_LOGIN_DAYS, settings.weeklyCutlines.weeklyLoginDays.encoded())
            setProperty(KEY_WEEKLY_PATROL_DAYS, settings.weeklyCutlines.weeklyPatrolDays.encoded())
            settings.payloadHistory.toSortedMap().forEach { (payloadType, enabled) ->
                setProperty(payloadKey(payloadType), enabled.toString())
            }
        }
        return ByteArrayOutputStream().also { output ->
            properties.store(output, "mobileGF2logger complete app settings")
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): AppBackupSettings {
        val properties = Properties().apply {
            ByteArrayInputStream(bytes).use(::load)
        }
        require(properties.stringPropertyNames() == expectedKeys()) {
            "Backup settings are incomplete or contain unknown fields"
        }
        require(properties.required(KEY_SCHEMA_VERSION) == SCHEMA_VERSION.toString()) {
            "Unsupported settings schema"
        }
        val settings = AppBackupSettings(
            language = properties.required(KEY_LANGUAGE),
            detailedNotifications = properties.strictBoolean(KEY_DETAILED_NOTIFICATIONS),
            targetPackage = properties.required(KEY_TARGET_PACKAGE),
            payloadHistory = PayloadCatalog.categories.associate { category ->
                category.payloadType to properties.strictBoolean(payloadKey(category.payloadType))
            },
            memberOrder = properties.memberOrder(),
            weeklyCutlines = WeeklyCutlines(
                dailyMerit = properties.optionalLong(KEY_DAILY_MERIT),
                dailyGunsmokeScore = properties.optionalLong(KEY_DAILY_SCORE),
                dailyGunsmokeAttempts = properties.optionalInt(KEY_DAILY_ATTEMPTS),
                weeklyMerit = properties.optionalLong(KEY_WEEKLY_MERIT),
                weeklyGunsmokeScore = properties.optionalLong(KEY_WEEKLY_SCORE),
                weeklyGunsmokeAttempts = properties.optionalInt(KEY_WEEKLY_ATTEMPTS),
                weeklyLoginDays = properties.optionalInt(KEY_WEEKLY_LOGIN_DAYS),
                weeklyPatrolDays = properties.optionalInt(KEY_WEEKLY_PATROL_DAYS),
            ),
        )
        validate(settings)
        return settings
    }

    private fun validate(settings: AppBackupSettings) {
        require(settings.language in setOf("en", "ko")) { "Unsupported display language" }
        require(
            settings.targetPackage.length in 3..255 &&
                PACKAGE_NAME.matches(settings.targetPackage),
        ) { "Invalid target package" }
        val payloadTypes = PayloadCatalog.categories.map { it.payloadType }.toSet()
        require(settings.payloadHistory.keys == payloadTypes) { "Payload settings are incomplete" }
        PayloadCatalog.categories.filter { it.isRequired }.forEach { category ->
            require(settings.payloadHistory[category.payloadType] == true) {
                "Required payload history cannot be disabled"
            }
        }
        require(settings.memberOrder.all { it > 0L }) { "Member order contains an invalid UID" }
        require(settings.memberOrder.distinct().size == settings.memberOrder.size) {
            "Member order contains duplicate UIDs"
        }
        settings.weeklyCutlines.run {
            requireNonNegative(dailyMerit, KEY_DAILY_MERIT)
            requireNonNegative(dailyGunsmokeScore, KEY_DAILY_SCORE)
            requireInRange(dailyGunsmokeAttempts, 0..3, KEY_DAILY_ATTEMPTS)
            requireNonNegative(weeklyMerit, KEY_WEEKLY_MERIT)
            requireNonNegative(weeklyGunsmokeScore, KEY_WEEKLY_SCORE)
            requireInRange(weeklyGunsmokeAttempts, 0..21, KEY_WEEKLY_ATTEMPTS)
            requireInRange(weeklyLoginDays, 0..7, KEY_WEEKLY_LOGIN_DAYS)
            requireInRange(weeklyPatrolDays, 0..7, KEY_WEEKLY_PATROL_DAYS)
        }
    }

    private fun expectedKeys(): Set<String> = BASE_KEYS +
        PayloadCatalog.categories.map { payloadKey(it.payloadType) }

    private fun payloadKey(payloadType: Int) = "payloadHistory.$payloadType"

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "Backup setting $key is missing" }

    private fun Properties.strictBoolean(key: String): Boolean = when (val value = required(key)) {
        "true" -> true
        "false" -> false
        else -> error("Backup setting $key is not a Boolean")
    }

    private fun Properties.optionalLong(key: String): Long? {
        val value = required(key)
        if (value == NONE) return null
        val parsed = value.toLongOrNull()
        require(parsed != null && parsed.toString() == value) { "Backup setting $key is invalid" }
        return parsed
    }

    private fun Properties.optionalInt(key: String): Int? {
        val value = optionalLong(key) ?: return null
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Backup setting $key is out of range" }
        return value.toInt()
    }

    private fun Properties.memberOrder(): List<Long> {
        val value = required(KEY_MEMBER_ORDER)
        if (value.isEmpty()) return emptyList()
        return value.split(',').map { encoded ->
            requireNotNull(encoded.toLongOrNull()?.takeIf { it.toString() == encoded }) {
                "Backup member order is invalid"
            }
        }
    }

    private fun Long?.encoded() = this?.toString() ?: NONE
    private fun Int?.encoded() = this?.toString() ?: NONE

    private fun requireNonNegative(value: Long?, key: String) {
        require(value == null || value >= 0L) { "Backup setting $key is out of range" }
    }

    private fun requireInRange(value: Int?, range: IntRange, key: String) {
        require(value == null || value in range) { "Backup setting $key is out of range" }
    }

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val BASE_KEYS = setOf(
        KEY_SCHEMA_VERSION,
        KEY_LANGUAGE,
        KEY_DETAILED_NOTIFICATIONS,
        KEY_TARGET_PACKAGE,
        KEY_MEMBER_ORDER,
        KEY_DAILY_MERIT,
        KEY_DAILY_SCORE,
        KEY_DAILY_ATTEMPTS,
        KEY_WEEKLY_MERIT,
        KEY_WEEKLY_SCORE,
        KEY_WEEKLY_ATTEMPTS,
        KEY_WEEKLY_LOGIN_DAYS,
        KEY_WEEKLY_PATROL_DAYS,
    )
}
