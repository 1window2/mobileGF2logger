package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.app.SupportedGamePackages

/** Stores the server region used to route each supported publisher's captured profile. */
internal class ClientServerRegionPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun get(packageName: String): GameServerRegion {
        require(packageName in SupportedGamePackages.all)
        return configured(packageName) ?: default(packageName)
    }

    /**
     * Returns only a persisted or migrated operator choice, never a guessed default. The pre-2.4
     * global region seeds each client at most once; after that only this store answers, so a
     * later [clear] leaves the client unconfigured.
     */
    fun configured(packageName: String): GameServerRegion? {
        require(packageName in SupportedGamePackages.all)
        stored(packageName)?.let { return it }
        if (preferences.getBoolean(legacyMigratedKey(packageName), false)) return null
        val legacy = legacySeed(GameTimeZonePreferences.legacyRegion(appContext), packageName)
        val editor = preferences.edit().putBoolean(legacyMigratedKey(packageName), true)
        legacy?.let { editor.putString(packageName, it.storedValue) }
        // Memory is updated at once; a write that never reaches the disk is retried by the next process.
        editor.apply()
        return legacy
    }

    fun set(packageName: String, region: GameServerRegion) {
        require(region in allowed(packageName))
        check(preferences.edit().putString(packageName, region.storedValue).commit()) {
            "Unable to persist the client server region"
        }
    }

    fun stored(packageName: String): GameServerRegion? {
        require(packageName in SupportedGamePackages.all)
        val value = preferences.getString(packageName, null) ?: return null
        return GameServerRegion.fromStored(value).takeIf { it in allowed(packageName) }
    }

    fun clear(packageName: String) {
        require(packageName in SupportedGamePackages.all)
        // Marking the migration done keeps the legacy global region from answering again.
        check(preferences.edit().remove(packageName).putBoolean(legacyMigratedKey(packageName), true).commit()) {
            "Unable to clear the client server region"
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

        private fun legacyMigratedKey(packageName: String) = "legacy_migrated.$packageName"

        /** The legacy global region when [packageName]'s publisher runs it; otherwise nothing. */
        fun legacySeed(legacy: GameServerRegion, packageName: String): GameServerRegion? =
            legacy.takeIf { it in allowedFor(packageName) }

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
