package dev.gf2log.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.StringRes
import dev.gf2log.app.MainActivity
import dev.gf2log.app.R
import dev.gf2log.app.settings.CapturePreferences

/**
 * The foreground notification Android requires while VPN capture is active. Tapping it opens the
 * app, and its Stop action ends capture as Home's stop button does.
 */
internal class CaptureNotifications(private val service: Service) {
    private val preferences = CapturePreferences(service)
    private val manager = service.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                service.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun startForeground(@StringRes content: Int) {
        val notification = build(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(ID, notification)
        }
    }

    fun update(@StringRes content: Int) = manager.notify(ID, build(content))

    fun remove() = service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

    private fun build(@StringRes content: Int): Notification =
        Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(
                service.getString(
                    if (preferences.detailedNotifications) content else R.string.notification_capture_active,
                ),
            )
            .setOngoing(true)
            // Outside the app this notification is the capture's only control.
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    REQUEST_OPEN,
                    openIntent(service),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_stop),
                    service.getString(R.string.stop_capture),
                    PendingIntent.getService(
                        service,
                        REQUEST_STOP,
                        stopIntent(service),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .build()

    internal companion object {
        private const val CHANNEL = "capture"
        private const val ID = 1
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2

        /** Brings the app's task forward the way its launcher icon does. */
        fun openIntent(context: Context): Intent =
            Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP)
    }
}
