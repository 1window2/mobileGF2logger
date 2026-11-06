package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import java.time.ZoneId

internal interface BackupSettingsStore {
    fun read(): AppBackupSettings

    fun replace(settings: AppBackupSettings)
}

internal class AppSettingsStore(context: Context) : BackupSettingsStore {
    private val appContext = context.applicationContext

    override fun read(): AppBackupSettings = UserSettingsPreferences.read(appContext)

    override fun replace(settings: AppBackupSettings) =
        UserSettingsPreferences.replace(appContext, settings)
}

/**
 * Projects global presentation settings plus one Platoon's isolated reporting settings.
 * [restoredProfile] names the profile a restore installs after these settings; otherwise the
 * scope's registered profile describes the Platoon.
 */
internal class ScopedAppSettingsStore(
    context: Context,
    private val storageId: String,
    private val restoredProfile: PlatoonProfile? = null,
) : BackupSettingsStore {
    private val appContext = context.applicationContext

    override fun read(): AppBackupSettings {
        val global = UserSettingsPreferences.read(appContext)
        return global.copy(
            gameServerRegion = GameTimeZonePreferences.region(appContext, storageId).storedValue,
            gameTimeZoneId = GameTimeZonePreferences.get(appContext, storageId).id,
            memberOrder = MemberOrderPreferences(appContext, storageId).read(),
            weeklyCutlines = WeeklyCutlinePreferences(appContext, storageId).read(),
        )
    }

    /**
     * Replaces four preference files, which SharedPreferences cannot commit atomically. Every
     * value is validated before the first write, and each committed file is restored in reverse
     * order if a later one fails, so a failure leaves the previous settings in place.
     */
    override fun replace(settings: AppBackupSettings) {
        AppBackupSettingsCodec.encode(settings)
        val region = GameServerRegion.fromStored(settings.gameServerRegion)
        val manualZone = if (region == GameServerRegion.MANUAL) ZoneId.of(settings.gameTimeZoneId) else null
        val previousGlobal = UserSettingsPreferences.read(appContext)
        val memberOrder = MemberOrderPreferences(appContext, storageId)
        val cutlines = WeeklyCutlinePreferences(appContext, storageId)
        val previousOrder = memberOrder.read()
        val previousCutlines = cutlines.read()
        val previousZone = GameTimeZonePreferences.scopedStateOrNull(appContext, storageId)
        val profile = (restoredProfile ?: PlatoonProfileRegistry(appContext).find(storageId))
            ?.takeUnless(PlatoonProfile::legacy)
        val rollbacks = ArrayDeque<() -> Unit>()
        try {
            UserSettingsPreferences.replace(
                appContext,
                settings.copy(
                    gameServerRegion = previousGlobal.gameServerRegion,
                    gameTimeZoneId = previousGlobal.gameTimeZoneId,
                    memberOrder = previousGlobal.memberOrder,
                    weeklyCutlines = previousGlobal.weeklyCutlines,
                ),
            )
            rollbacks.addFirst { UserSettingsPreferences.replace(appContext, previousGlobal) }
            if (previousZone != null) {
                rollbacks.addFirst {
                    GameTimeZonePreferences.restoreScopedState(appContext, storageId, previousZone)
                }
            }
            when {
                manualZone != null -> GameTimeZonePreferences.set(appContext, manualZone, storageId)
                ScopedResetRegionPolicy.restoresAutomatic(
                    region = region,
                    profileRegion = profile?.serverRegion,
                    allowed = profile?.let { ClientServerRegionPreferences.allowedFor(it.client.packageName) },
                ) -> GameTimeZonePreferences.clearRegionOverride(appContext, storageId)
                else -> GameTimeZonePreferences.setRegion(appContext, region, storageId)
            }
            rollbacks.addFirst { check(memberOrder.write(previousOrder)) }
            check(memberOrder.write(settings.memberOrder)) {
                "Unable to restore the Platoon member order"
            }
            rollbacks.addFirst { cutlines.write(previousCutlines) }
            cutlines.write(settings.weeklyCutlines)
        } catch (error: Exception) {
            rollbacks.forEach { rollback ->
                runCatching(rollback).exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }
}

/**
 * Settings record only a scope's effective reset region, so replaying them must not turn the
 * automatic choice into an explicit override, and a region the Platoon's publisher does not run
 * can never become its reset region.
 */
internal object ScopedResetRegionPolicy {
    /**
     * True when restoring non-manual [region] clears the scope's override: it equals the profile's
     * own region, or the profile's publisher does not run it. With no known profile, the region
     * stays explicit and the restore reconciles it once the profile is installed.
     */
    fun restoresAutomatic(
        region: GameServerRegion,
        profileRegion: GameServerRegion?,
        allowed: List<GameServerRegion>?,
    ): Boolean {
        if (region == GameServerRegion.MANUAL || profileRegion == null || allowed == null) return false
        return region == profileRegion || region !in allowed
    }
}
