package dev.gf2log.app

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gf2log.app.capture.CaptureNotice
import dev.gf2log.app.capture.CaptureNoticeEvent
import dev.gf2log.app.capture.CapturePhase
import dev.gf2log.app.capture.CaptureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeCapturePanelIntegrationTest {
    @Test
    fun longErrorNoticeWrapsInFullAtDoubleFontScale() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val large = base.createConfigurationContext(
            Configuration(base.resources.configuration).apply { fontScale = 2f },
        )
        lateinit var detail: android.widget.TextView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val panel = HomeCapturePanel(large, onStart = {}, onStop = {})
            panel.render(
                CaptureSnapshot(
                    phase = CapturePhase.STOPPED,
                    notice = CaptureNoticeEvent(CaptureNotice.CLIENT_SIGNER_CHANGED, atMillis = 1L),
                    capturedTargets = emptyList(),
                    guided = null,
                ),
            )
            // About the panel's width inside a 360dp phone's gutters.
            val width = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                300f,
                large.resources.displayMetrics,
            ).toInt()
            panel.view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            panel.view.layout(0, 0, panel.view.measuredWidth, panel.view.measuredHeight)
            detail = panel.stateDetail
        }

        val expected = large.getString(R.string.capture_notice_client_signer_changed)
        assertEquals(expected, detail.text.toString())
        val layout = requireNotNull(detail.layout)
        assertTrue("The notice should need several lines at this scale", layout.lineCount > 4)
        assertEquals(0, layout.getEllipsisCount(layout.lineCount - 1))
        assertEquals(expected.length, layout.getLineEnd(layout.lineCount - 1))
    }
}
