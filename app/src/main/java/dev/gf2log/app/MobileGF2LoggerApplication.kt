package dev.gf2log.app

import android.app.Application
import dev.gf2log.app.management.PlatoonBackupManager

class MobileGF2LoggerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlatoonBackupManager.recoverInterruptedFullRestore(this)
    }
}
