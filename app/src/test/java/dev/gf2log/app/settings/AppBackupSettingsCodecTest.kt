package dev.gf2log.app.settings

import dev.gf2log.protocol.PayloadCatalog
import dev.gf2log.protocol.Gfl2PayloadDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppBackupSettingsCodecTest {
    @Test
    fun `round trips every supported user setting`() {
        val settings = completeSettings()

        val restored = AppBackupSettingsCodec.decode(AppBackupSettingsCodec.encode(settings))

        assertEquals(settings, restored)
    }

    @Test
    fun `restores legacy settings with safe display defaults`() {
        val legacy = encodedProperties().apply {
            setProperty("schemaVersion", "1")
            remove("themeMode")
            remove("gameTimeZone")
            remove("gameServerRegion")
            remove("onboardingCompleted")
            remove("payloadHistory.${Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE}")
        }

        val restored = AppBackupSettingsCodec.decode(legacy.toBytes())

        assertEquals("system", restored.themeMode)
        assertEquals(true, restored.onboardingCompleted)
    }

    @Test
    fun `restores schema two settings with the current device timezone`() {
        val schemaTwo = encodedProperties().apply {
            setProperty("schemaVersion", "2")
            remove("gameTimeZone")
            remove("gameServerRegion")
            remove("payloadHistory.${Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE}")
        }

        val restored = AppBackupSettingsCodec.decode(schemaTwo.toBytes())

        assertEquals(java.time.ZoneId.systemDefault().id, restored.gameTimeZoneId)
    }

    @Test
    fun `restores schema three settings as manual server selection`() {
        val schemaThree = encodedProperties().apply {
            setProperty("schemaVersion", "3")
            remove("gameServerRegion")
            remove("payloadHistory.${Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE}")
        }

        val restored = AppBackupSettingsCodec.decode(schemaThree.toBytes())

        assertEquals(GameServerRegion.MANUAL.storedValue, restored.gameServerRegion)
    }

    @Test
    fun `rejects a missing required setting`() {
        val properties = encodedProperties()
        properties.remove("language")

        assertThrows(IllegalArgumentException::class.java) {
            AppBackupSettingsCodec.decode(properties.toBytes())
        }
    }

    @Test
    fun `rejects unknown settings`() {
        val properties = encodedProperties()
        properties.setProperty("captureDiagnostics", "included")

        assertThrows(IllegalArgumentException::class.java) {
            AppBackupSettingsCodec.decode(properties.toBytes())
        }
    }

    @Test
    fun `rejects duplicate logical settings`() {
        val duplicate = AppBackupSettingsCodec.encode(completeSettings()) +
            "\nlanguage=ko\n".toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            AppBackupSettingsCodec.decode(duplicate)
        }
    }

    @Test
    fun `rejects malformed values and disabled required payloads`() {
        val malformedBoolean = encodedProperties().apply {
            setProperty("detailedNotifications", "yes")
        }
        val requiredPayload = PayloadCatalog.categories.first { it.isRequired }.payloadType
        val disabledRequiredPayload = encodedProperties().apply {
            setProperty("payloadHistory.$requiredPayload", "false")
        }

        assertThrows(IllegalArgumentException::class.java) {
            AppBackupSettingsCodec.decode(malformedBoolean.toBytes())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppBackupSettingsCodec.decode(disabledRequiredPayload.toBytes())
        }
    }

    @Test
    fun `rejects invalid package member order and cutline ranges`() {
        val invalidPackage = encodedProperties().apply {
            setProperty("targetPackage", "not a package")
        }
        val duplicateMembers = encodedProperties().apply {
            setProperty("memberOrder", "42,42")
        }
        val invalidAttempts = encodedProperties().apply {
            setProperty("cutline.dailyGunsmokeAttempts", "4")
        }

        listOf(invalidPackage, duplicateMembers, invalidAttempts).forEach { properties ->
            assertThrows(IllegalArgumentException::class.java) {
                AppBackupSettingsCodec.decode(properties.toBytes())
            }
        }
    }

    private fun encodedProperties(): Properties = Properties().apply {
        ByteArrayInputStream(AppBackupSettingsCodec.encode(completeSettings())).use(::load)
    }

    private fun completeSettings() = AppBackupSettings(
        language = "ko",
        themeMode = "dark",
        gameServerRegion = GameServerRegion.HAOPLAY_KOREA.storedValue,
        gameTimeZoneId = "Asia/Seoul",
        onboardingCompleted = true,
        detailedNotifications = false,
        targetPackage = "com.example.game_client",
        payloadHistory = PayloadCatalog.categories.associate { category ->
            category.payloadType to (category.isRequired || category.payloadType % 2 == 0)
        },
        memberOrder = listOf(1001L, 2002L, 3003L),
        weeklyCutlines = WeeklyCutlines(
            dailyMerit = 90,
            dailyGunsmokeScore = 10_000,
            dailyGunsmokeAttempts = 3,
            weeklyMerit = 600,
            weeklyGunsmokeScore = 70_000,
            weeklyGunsmokeAttempts = 21,
            weeklyLoginDays = 7,
            weeklyPatrolDays = 5,
        ),
    )

    private fun Properties.toBytes(): ByteArray = ByteArrayOutputStream().also { output ->
        store(output, null)
    }.toByteArray()
}
