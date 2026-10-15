package dev.gf2log.app.settings

import android.content.Context
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

/** Projects global presentation settings plus one Platoon's isolated reporting settings. */
internal class ScopedAppSettingsStore(
    context: Context,
    private val storageId: String,
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
            if (manualZone != null) {
                GameTimeZonePreferences.set(appContext, manualZone, storageId)
            } else {
                GameTimeZonePreferences.setRegion(appContext, region, storageId)
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
