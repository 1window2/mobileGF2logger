package dev.gf2log.app.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureNotificationsIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun tappingTheNotificationOpensTheAppLikeItsLauncherIcon() {
        val intent = CaptureNotifications.openIntent(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun theStopActionTargetsOnlyTheCaptureService() {
        val intent = CaptureNotifications.stopIntent(context)

        assertEquals(CaptureVpnService::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(CaptureVpnService.ACTION_STOP, intent.action)
    }
}
