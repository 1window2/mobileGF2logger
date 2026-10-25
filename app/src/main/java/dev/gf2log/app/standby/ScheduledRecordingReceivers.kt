package dev.gf2log.app.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

/** The scheduled-recording alarm. Not exported: only this app's own alarm can reach it. */
class ScheduledRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledRecording.ACTION_FIRE) return
        val reset = intent.getLongExtra(ScheduledRecording.EXTRA_RESET, -1).takeIf { it >= 0 } ?: return
        val keys = intent.getStringArrayExtra(ScheduledRecording.EXTRA_SERVERS)?.toSet().orEmpty()
        // Kept alive until the next wake-up is set, even when nothing else holds the process.
        val pending = goAsync()
        ScheduledRecording.onAlarm(context, Instant.ofEpochMilli(reset), keys) { pending.finish() }
    }
}

/**
 * Sets the next scheduled recording again after a reboot, an app update, a clock or time zone
 * change, or a change to the exact-alarm permission; alarms do not survive the first two.
 */
class ScheduledRecordingRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        ScheduledRecording.reschedule(context) { pending.finish() }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, Android 12+.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
