package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.app.management.PlatoonProfileIdentity
import dev.gf2log.app.management.PlatoonProfileRegistry
import java.time.ZoneId

/** Provides the one persisted timezone used for every game-day and weekly boundary. */
internal object GameTimeZonePreferences {
    fun get(
        context: Context,
        storageId: String = PlatoonProfileRegistry(context).activeScope().storageId,
    ): ZoneId {
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) return legacyZone(context)
        val region = region(context, storageId)
        return region.serverZone ?: runCatching {
            ZoneId.of(scoped(context).getString(zoneKey(storageId), ZoneId.systemDefault().id))
        }.getOrElse { ZoneId.systemDefault() }
    }

    fun deviceZone(): ZoneId = ZoneId.systemDefault()

    fun region(
        context: Context,
        storageId: String = PlatoonProfileRegistry(context).activeScope().storageId,
    ): GameServerRegion {
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) return legacyRegion(context)
        val stored = scoped(context).getString(regionKey(storageId), null)
        if (stored != null) return GameServerRegion.fromStored(stored)
        return PlatoonProfileRegistry(context).find(storageId)?.serverRegion ?: GameServerRegion.MANUAL
    }

    fun setRegion(
        context: Context,
        region: GameServerRegion,
        storageId: String = PlatoonProfileRegistry(context).activeScope().storageId,
    ) {
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) {
            UserSettingsPreferences.setGameServerRegion(context, region.storedValue)
        } else {
            check(scoped(context).edit().putString(regionKey(storageId), region.storedValue).commit())
        }
    }

    fun set(
        context: Context,
        zoneId: ZoneId,
        storageId: String = PlatoonProfileRegistry(context).activeScope().storageId,
    ) {
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) {
            UserSettingsPreferences.setGameTimeZoneId(context, zoneId.id)
        } else {
            check(scoped(context).edit().putString(zoneKey(storageId), zoneId.id).commit())
        }
        setRegion(context, GameServerRegion.MANUAL, storageId)
    }

    fun legacyRegion(context: Context): GameServerRegion = GameServerRegion.fromStored(
        UserSettingsPreferences.gameServerRegion(context),
    )

    private fun legacyZone(context: Context): ZoneId {
        val region = legacyRegion(context)
        return region.serverZone ?: runCatching {
            ZoneId.of(UserSettingsPreferences.gameTimeZoneId(context))
        }.getOrElse { ZoneId.systemDefault() }
    }

    private fun scoped(context: Context) = context.applicationContext.getSharedPreferences(
        PROFILE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun regionKey(storageId: String) = "region.$storageId"
    private fun zoneKey(storageId: String) = "zone.$storageId"
    private const val PROFILE_PREFERENCES = "platoon_timezones"
}
