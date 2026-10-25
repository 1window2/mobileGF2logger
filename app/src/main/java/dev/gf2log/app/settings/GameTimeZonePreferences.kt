package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.app.management.PlatoonProfileIdentity
import dev.gf2log.app.management.PlatoonProfileRegistry
import java.time.ZoneId

/** Provides the one persisted timezone used for every game-day and weekly boundary. */
internal object GameTimeZonePreferences {
    fun get(
        context: Context,
        storageId: String,
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
        storageId: String,
    ): GameServerRegion {
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) return legacyRegion(context)
        val stored = scoped(context).getString(regionKey(storageId), null)
        if (stored != null) return GameServerRegion.fromStored(stored)
        return PlatoonProfileRegistry(context).find(storageId)?.serverRegion ?: GameServerRegion.MANUAL
    }

    fun isAutomatic(context: Context, storageId: String): Boolean =
        storageId != PlatoonProfileIdentity.LEGACY_STORAGE_ID &&
            !scoped(context).contains(regionKey(storageId))

    /** Returns the Platoon to its profile's server region; removes both scoped keys. */
    fun clearRegionOverride(context: Context, storageId: String) =
        restoreScopedState(context, storageId, ScopedZoneState(region = null, zone = null))

    /** Profile deletion leaves no timezone keys behind; identical to [clearRegionOverride]. */
    fun clearScope(context: Context, storageId: String) = clearRegionOverride(context, storageId)

    /** The raw scoped keys, so a failed settings replacement can restore them exactly. */
    internal data class ScopedZoneState(val region: String?, val zone: String?)

    internal fun scopedState(context: Context, storageId: String): ScopedZoneState {
        require(storageId != PlatoonProfileIdentity.LEGACY_STORAGE_ID)
        val preferences = scoped(context)
        return ScopedZoneState(
            region = preferences.getString(regionKey(storageId), null),
            zone = preferences.getString(zoneKey(storageId), null),
        )
    }

    /** Null for the legacy scope, whose timezone lives in the unified settings store. */
    internal fun scopedStateOrNull(context: Context, storageId: String): ScopedZoneState? =
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) null else scopedState(context, storageId)

    internal fun restoreScopedState(context: Context, storageId: String, state: ScopedZoneState) {
        require(storageId != PlatoonProfileIdentity.LEGACY_STORAGE_ID)
        val editor = scoped(context).edit()
        if (state.region == null) {
            editor.remove(regionKey(storageId))
        } else {
            editor.putString(regionKey(storageId), state.region)
        }
        if (state.zone == null) editor.remove(zoneKey(storageId)) else editor.putString(zoneKey(storageId), state.zone)
        check(editor.commit()) { "Unable to update Platoon timezone settings" }
    }

    fun setRegion(
        context: Context,
        region: GameServerRegion,
        storageId: String,
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
        storageId: String,
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
