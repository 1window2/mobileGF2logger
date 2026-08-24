package dev.gf2log.app.settings

import android.content.Context

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

    override fun replace(settings: AppBackupSettings) {
        val previousGlobal = UserSettingsPreferences.read(appContext)
        UserSettingsPreferences.replace(
            appContext,
            settings.copy(
                gameServerRegion = previousGlobal.gameServerRegion,
                gameTimeZoneId = previousGlobal.gameTimeZoneId,
                memberOrder = previousGlobal.memberOrder,
                weeklyCutlines = previousGlobal.weeklyCutlines,
            ),
        )
        val region = GameServerRegion.fromStored(settings.gameServerRegion)
        if (region == GameServerRegion.MANUAL) {
            GameTimeZonePreferences.set(
                appContext,
                java.time.ZoneId.of(settings.gameTimeZoneId),
                storageId,
            )
        } else {
            GameTimeZonePreferences.setRegion(appContext, region, storageId)
        }
        check(MemberOrderPreferences(appContext, storageId).write(settings.memberOrder)) {
            "Unable to restore the Platoon member order"
        }
        WeeklyCutlinePreferences(appContext, storageId).write(settings.weeklyCutlines)
    }
}
