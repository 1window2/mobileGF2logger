package dev.gf2log.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.protocol.model.PlatoonProfileData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScopedRegionSettingsIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearState()
    }

    @After
    fun tearDown() = clearState()

    @Test
    fun theLegacyGlobalRegionSeedsAClientOnlyOnce() {
        legacyGlobalRegion(GameServerRegion.HAOPLAY_KOREA)
        val preferences = ClientServerRegionPreferences(context)

        assertEquals(GameServerRegion.HAOPLAY_KOREA, preferences.configured(SupportedGamePackages.HAOPLAY))
        assertEquals(GameServerRegion.HAOPLAY_KOREA, preferences.stored(SupportedGamePackages.HAOPLAY))
        assertNull(preferences.configured(SupportedGamePackages.DARKWINTER))

        preferences.clear(SupportedGamePackages.HAOPLAY)

        assertNull(preferences.configured(SupportedGamePackages.HAOPLAY))
        // The global key itself is left untouched.
        assertEquals(GameServerRegion.HAOPLAY_KOREA.storedValue, UserSettingsPreferences.gameServerRegion(context))
    }

    @Test
    fun clearingBeforeAnyMigrationStillSticks() {
        legacyGlobalRegion(GameServerRegion.HAOPLAY_KOREA)
        val preferences = ClientServerRegionPreferences(context)

        preferences.clear(SupportedGamePackages.HAOPLAY)

        assertNull(preferences.configured(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun aManualLegacyRegionConfiguresNoClient() {
        legacyGlobalRegion(GameServerRegion.MANUAL)
        val preferences = ClientServerRegionPreferences(context)

        assertNull(preferences.configured(SupportedGamePackages.HAOPLAY))
        assertNull(preferences.configured(SupportedGamePackages.DARKWINTER))
    }

    @Test
    fun aSettingsRoundTripKeepsAnAutomaticResetRegionAutomatic() {
        val storageId = haoPlayKoreaProfile()
        val store = ScopedAppSettingsStore(context, storageId)
        assertTrue(GameTimeZonePreferences.isAutomatic(context, storageId))

        store.replace(store.read())

        assertTrue(GameTimeZonePreferences.isAutomatic(context, storageId))
        assertEquals(GameServerRegion.HAOPLAY_KOREA, GameTimeZonePreferences.region(context, storageId))
    }

    @Test
    fun aSettingsRoundTripKeepsADeliberateOverride() {
        val storageId = haoPlayKoreaProfile()
        GameTimeZonePreferences.setRegion(context, GameServerRegion.HAOPLAY_GLOBAL, storageId)
        val store = ScopedAppSettingsStore(context, storageId)

        store.replace(store.read())

        assertFalse(GameTimeZonePreferences.isAutomatic(context, storageId))
        assertEquals(GameServerRegion.HAOPLAY_GLOBAL, GameTimeZonePreferences.region(context, storageId))
    }

    @Test
    fun anotherPublishersRegionIsNotRestoredOntoAPlatoon() {
        val storageId = haoPlayKoreaProfile()
        val store = ScopedAppSettingsStore(context, storageId)

        store.replace(store.read().copy(gameServerRegion = GameServerRegion.DARKWINTER_GLOBAL.storedValue))

        assertTrue(GameTimeZonePreferences.isAutomatic(context, storageId))
        assertEquals(GameServerRegion.HAOPLAY_KOREA, GameTimeZonePreferences.region(context, storageId))
    }

    private fun haoPlayKoreaProfile(): String = PlatoonProfileRegistry(context).upsertDetected(
        SupportedGamePackages.HAOPLAY,
        GameServerRegion.HAOPLAY_KOREA,
        PlatoonProfileData(101817u, "Owls", 2u, 3u),
    ).storageId

    private fun legacyGlobalRegion(region: GameServerRegion) {
        UserSettingsPreferences.read(context)
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit()
            .putString("game_server_region", region.storedValue)
            .commit()
    }

    private fun clearState() {
        listOf(
            "user_settings",
            ClientServerRegionPreferences.PREFERENCES,
            "platoon_profiles",
            "platoon_timezones",
            "platoon_member_order",
            "platoon_weekly_cutlines",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
