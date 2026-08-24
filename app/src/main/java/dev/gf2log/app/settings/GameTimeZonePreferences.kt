package dev.gf2log.app.settings

import android.content.Context
import java.time.ZoneId

/** Provides the one persisted timezone used for every game-day and weekly boundary. */
internal object GameTimeZonePreferences {
    fun get(context: Context): ZoneId {
        val region = region(context)
        return region.serverZone ?: runCatching {
            ZoneId.of(UserSettingsPreferences.gameTimeZoneId(context))
        }.getOrElse { ZoneId.systemDefault() }
    }

    fun deviceZone(): ZoneId = ZoneId.systemDefault()

    fun region(context: Context): GameServerRegion = GameServerRegion.fromStored(
        UserSettingsPreferences.gameServerRegion(context),
    )

    fun setRegion(context: Context, region: GameServerRegion) =
        UserSettingsPreferences.setGameServerRegion(context, region.storedValue)

    fun set(context: Context, zoneId: ZoneId) {
        UserSettingsPreferences.setGameTimeZoneId(context, zoneId.id)
        setRegion(context, GameServerRegion.MANUAL)
    }
}
