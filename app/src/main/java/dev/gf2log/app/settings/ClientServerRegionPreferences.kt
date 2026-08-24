package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.app.SupportedGamePackages

/** Stores the server region used to route each supported publisher's captured profile. */
internal class ClientServerRegionPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun get(packageName: String): GameServerRegion {
        require(packageName in SupportedGamePackages.all)
        val stored = GameServerRegion.fromStored(preferences.getString(packageName, null))
        if (stored in allowed(packageName)) return stored

        val legacy = GameTimeZonePreferences.legacyRegion(appContext)
        return legacy.takeIf { it in allowed(packageName) } ?: default(packageName)
    }

    fun set(packageName: String, region: GameServerRegion) {
        require(region in allowed(packageName))
        check(preferences.edit().putString(packageName, region.storedValue).commit()) {
            "Unable to persist the client server region"
        }
    }

    fun allowed(packageName: String): List<GameServerRegion> = allowedFor(packageName)

    private fun default(packageName: String): GameServerRegion = when (packageName) {
        SupportedGamePackages.HAOPLAY -> GameServerRegion.HAOPLAY_KOREA
        SupportedGamePackages.DARKWINTER -> GameServerRegion.DARKWINTER_GLOBAL
        else -> error("Unsupported game package")
    }

    internal companion object {
        const val PREFERENCES = "capture_server_regions"

        fun allowedFor(packageName: String): List<GameServerRegion> = when (packageName) {
            SupportedGamePackages.HAOPLAY -> listOf(
                GameServerRegion.HAOPLAY_GLOBAL,
                GameServerRegion.HAOPLAY_JAPAN,
                GameServerRegion.HAOPLAY_KOREA,
                GameServerRegion.HAOPLAY_ASIA,
            )
            SupportedGamePackages.DARKWINTER -> listOf(
                GameServerRegion.DARKWINTER_GLOBAL,
                GameServerRegion.DARKWINTER_CHINA,
            )
            else -> error("Unsupported game package")
        }
    }
}
