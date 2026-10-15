package dev.gf2log.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import dev.gf2log.app.R
import dev.gf2log.app.settings.CapturePreferences

/** The foreground notification Android requires while VPN capture is active. */
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
            .build()

    private companion object {
        const val CHANNEL = "capture"
        const val ID = 1
    }
}
