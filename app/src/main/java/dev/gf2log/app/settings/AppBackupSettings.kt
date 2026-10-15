package dev.gf2log.app.settings

import dev.gf2log.app.StrictProperties
import dev.gf2log.protocol.PayloadCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.Properties

data class AppBackupSettings(
    val language: String,
    val themeMode: String,
    val gameServerRegion: String,
    val gameTimeZoneId: String,
    val onboardingCompleted: Boolean,
    val detailedNotifications: Boolean,
    val targetPackage: String,
    val payloadHistory: Map<Int, Boolean>,
    val memberOrder: List<Long>,
    val weeklyCutlines: WeeklyCutlines,
)

object AppBackupSettingsCodec {
    private const val SCHEMA_VERSION = 4
    private const val TIME_ZONE_SCHEMA_VERSION = 3
    private const val THEME_SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val NONE = "none"
    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME_MODE = "themeMode"
    private const val KEY_GAME_SERVER_REGION = "gameServerRegion"
    private const val KEY_GAME_TIME_ZONE = "gameTimeZone"
    private const val KEY_ONBOARDING_COMPLETED = "onboardingCompleted"
    private const val KEY_DETAILED_NOTIFICATIONS = "detailedNotifications"
    private const val KEY_TARGET_PACKAGE = "targetPackage"
    private const val KEY_MEMBER_ORDER = "memberOrder"

    fun encode(settings: AppBackupSettings): ByteArray {
        validate(settings)
        val properties = Properties().apply {
            setProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION.toString())
            setProperty(KEY_LANGUAGE, settings.language)
            setProperty(KEY_THEME_MODE, settings.themeMode)
            setProperty(KEY_GAME_SERVER_REGION, settings.gameServerRegion)
            setProperty(KEY_GAME_TIME_ZONE, settings.gameTimeZoneId)
            setProperty(KEY_ONBOARDING_COMPLETED, settings.onboardingCompleted.toString())
            setProperty(KEY_DETAILED_NOTIFICATIONS, settings.detailedNotifications.toString())
            setProperty(KEY_TARGET_PACKAGE, settings.targetPackage)
            setProperty(KEY_MEMBER_ORDER, settings.memberOrder.joinToString(","))
            WeeklyCutlineField.entries.forEach { field ->
                setProperty(field.backupKey, field.valueIn(settings.weeklyCutlines)?.toString() ?: NONE)
            }
            settings.payloadHistory.toSortedMap().forEach { (payloadType, enabled) ->
                setProperty(payloadKey(payloadType), enabled.toString())
            }
        }
        return ByteArrayOutputStream().also { output ->
            properties.store(output, "mobileGF2logger complete app settings")
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): AppBackupSettings {
        val properties = StrictProperties("Backup settings").apply {
            ByteArrayInputStream(bytes).use(::load)
        }
        val schemaVersion = requireNotNull(properties.required(KEY_SCHEMA_VERSION).toIntOrNull()) {
            "Backup settings schema is invalid"
        }
        require(schemaVersion in LEGACY_SCHEMA_VERSION..SCHEMA_VERSION) {
            "Unsupported settings schema"
        }
        require(properties.stringPropertyNames() == expectedKeys(schemaVersion)) {
            "Backup settings are incomplete or contain unknown fields"
        }
        val settings = AppBackupSettings(
            language = properties.required(KEY_LANGUAGE),
            themeMode = if (schemaVersion >= THEME_SCHEMA_VERSION) {
                properties.required(KEY_THEME_MODE)
            } else {
                "system"
            },
            gameServerRegion = if (schemaVersion >= SCHEMA_VERSION) {
                properties.required(KEY_GAME_SERVER_REGION)
            } else {
                GameServerRegion.MANUAL.storedValue
            },
            gameTimeZoneId = if (schemaVersion >= TIME_ZONE_SCHEMA_VERSION) {
                properties.required(KEY_GAME_TIME_ZONE)
            } else {
                ZoneId.systemDefault().id
            },
            onboardingCompleted = if (schemaVersion >= THEME_SCHEMA_VERSION) {
                properties.strictBoolean(KEY_ONBOARDING_COMPLETED)
            } else {
                true
            },
            detailedNotifications = properties.strictBoolean(KEY_DETAILED_NOTIFICATIONS),
            targetPackage = properties.required(KEY_TARGET_PACKAGE),
            payloadHistory = PayloadCatalog.categories.associate { category ->
                category.payloadType to if (
                    schemaVersion < SCHEMA_VERSION &&
                    category.payloadType == dev.gf2log.protocol.Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE
                ) {
                    true
                } else {
                    properties.strictBoolean(payloadKey(category.payloadType))
                }
            },
            memberOrder = properties.memberOrder(),
            weeklyCutlines = WeeklyCutlineField.cutlinesOf { field ->
                if (field.storedAsInt) {
                    properties.optionalInt(field.backupKey)?.toLong()
                } else {
                    properties.optionalLong(field.backupKey)
                }
            },
        )
        validate(settings)
        return settings
    }

    private fun validate(settings: AppBackupSettings) {
        require(settings.language in setOf("en", "ko")) { "Unsupported display language" }
        require(settings.themeMode in setOf("system", "light", "dark")) {
            "Unsupported display theme"
        }
        require(
            GameServerRegion.fromStored(settings.gameServerRegion).storedValue ==
                settings.gameServerRegion,
        ) { "Unsupported game server region" }
        require(runCatching { ZoneId.of(settings.gameTimeZoneId) }.isSuccess) {
            "Unsupported game timezone"
        }
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
        WeeklyCutlineField.entries.forEach { field ->
            val value = field.valueIn(settings.weeklyCutlines)
            require(value == null || value in field.range) {
                "Backup setting ${field.backupKey} is out of range"
            }
        }
    }

    private fun expectedKeys(schemaVersion: Int): Set<String> =
        when (schemaVersion) {
            SCHEMA_VERSION -> BASE_KEYS
            TIME_ZONE_SCHEMA_VERSION -> BASE_KEYS - KEY_GAME_SERVER_REGION
            THEME_SCHEMA_VERSION -> BASE_KEYS - setOf(KEY_GAME_SERVER_REGION, KEY_GAME_TIME_ZONE)
            else -> LEGACY_BASE_KEYS
        } + PayloadCatalog.categories
            .filterNot {
                schemaVersion < SCHEMA_VERSION &&
                    it.payloadType == dev.gf2log.protocol.Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE
            }
            .map { payloadKey(it.payloadType) }

    private fun payloadKey(payloadType: Int) = "payloadHistory.$payloadType"

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "Backup setting $key is missing" }

    private fun Properties.strictBoolean(key: String): Boolean = when (val value = required(key)) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Backup setting $key is not a Boolean")
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

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val BASE_KEYS = setOf(
        KEY_SCHEMA_VERSION,
        KEY_LANGUAGE,
        KEY_THEME_MODE,
        KEY_GAME_SERVER_REGION,
        KEY_GAME_TIME_ZONE,
        KEY_ONBOARDING_COMPLETED,
        KEY_DETAILED_NOTIFICATIONS,
        KEY_TARGET_PACKAGE,
        KEY_MEMBER_ORDER,
    ) + WeeklyCutlineField.entries.map(WeeklyCutlineField::backupKey)
    private val LEGACY_BASE_KEYS = BASE_KEYS -
        setOf(
            KEY_THEME_MODE,
            KEY_GAME_SERVER_REGION,
            KEY_GAME_TIME_ZONE,
            KEY_ONBOARDING_COMPLETED,
        )
}
