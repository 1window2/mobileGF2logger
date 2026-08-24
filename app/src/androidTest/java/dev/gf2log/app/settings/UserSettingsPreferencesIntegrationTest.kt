package dev.gf2log.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserSettingsPreferencesIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearSettings()
    }

    @After
    fun tearDown() {
        clearSettings()
    }

    @Test
    fun freshInstallKeepsOnboardingPending() {
        assertFalse(UserSettingsPreferences.onboardingCompleted(context))
    }

    @Test
    fun unifiedV1UpgradePreservesSettingsAndCompletesOnboarding() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit()
            .putInt("schema_version", 1)
            .putString("language", "ko")
            .putBoolean("detailed_notifications", false)
            .putString("target_package", "com.example.gf2")
            .commit()

        val settings = UserSettingsPreferences.read(context)

        assertEquals("ko", settings.language)
        assertEquals("com.example.gf2", settings.targetPackage)
        assertFalse(settings.detailedNotifications)
        assertEquals("system", settings.themeMode)
        assertEquals(GameServerRegion.MANUAL.storedValue, settings.gameServerRegion)
        assertEquals(java.time.ZoneId.systemDefault().id, settings.gameTimeZoneId)
        assertTrue(settings.onboardingCompleted)
        assertEquals(
            4,
            context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
                .getInt("schema_version", 0),
        )
    }

    private fun clearSettings() {
        listOf(
            "user_settings",
            "display_settings",
            "capture_preferences",
            "payload_history_options",
            "weekly_member_order",
            "weekly_cutlines",
            ".app.MainActivity",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
