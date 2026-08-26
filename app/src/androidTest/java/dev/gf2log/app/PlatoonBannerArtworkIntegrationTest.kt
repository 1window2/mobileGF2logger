package dev.gf2log.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatoonBannerArtworkIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun everyGameDefinedFrameAndMarkCombinationRendersVisibleArtwork() {
        for (frameId in 1L..6L) {
            for (markId in 1L..6L) {
                val artwork = assertNotNullAndReturn(
                    PlatoonBannerArtwork.drawable(context, frameId, markId),
                )
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                artwork.setBounds(0, 0, bitmap.width, bitmap.height)
                artwork.draw(Canvas(bitmap))

                assertTrue(
                    "Frame $frameId and mark $markId rendered no visible pixels",
                    bitmapHasVisiblePixel(bitmap),
                )
            }
        }
    }

    @Test
    fun unknownBannerIdsDoNotSelectArbitraryArtwork() {
        assertNull(PlatoonBannerArtwork.drawable(context, 0L, 1L))
        assertNull(PlatoonBannerArtwork.drawable(context, 1L, 0L))
        assertNull(PlatoonBannerArtwork.drawable(context, 7L, 1L))
        assertNull(PlatoonBannerArtwork.drawable(context, 1L, 7L))
    }

    private fun bitmapHasVisiblePixel(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { pixel -> (pixel ushr 24) != 0 }
    }

    private fun <T : Any> assertNotNullAndReturn(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }
}
