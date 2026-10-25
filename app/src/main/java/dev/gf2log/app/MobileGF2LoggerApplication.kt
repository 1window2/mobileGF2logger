package dev.gf2log.app

import android.app.Application
import android.util.Log
import dev.gf2log.app.management.CsvImportCheckpointManager
import dev.gf2log.app.management.PlatoonBackupManager

class MobileGF2LoggerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A recovery that keeps failing must not crash every launch; its durable state stays for
        // the next attempt, which also runs whenever that Platoon's repository is opened.
        try {
            PlatoonBackupManager.recoverInterruptedFullRestore(this)
        } catch (error: Exception) {
            Log.e(TAG, "Interrupted restore recovery failed", error)
        }
        // Interrupted CSV imports and undos finish off the main thread. Capture repeats the same
        // per-scope recovery before it ingests, and each scope's claim serializes the two.
        Thread({ CsvImportCheckpointManager.recoverInterruptedImports(this) }, "GF2StartupRecovery").start()
    }

    private companion object {
        const val TAG = "GF2Startup"
    }
}
