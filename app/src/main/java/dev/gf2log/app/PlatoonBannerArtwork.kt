package dev.gf2log.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import dev.gf2log.app.management.PlatoonProfile
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders the game's original frame and mark sprites as one Platoon emblem. */
internal object PlatoonBannerArtwork {
    fun drawable(context: Context, profile: PlatoonProfile): Drawable? = drawable(
        context = context,
        frameId = profile.bannerFrameId,
        markId = profile.bannerMarkId,
    )

    fun drawable(context: Context, frameId: Long, markId: Long): Drawable? {
        val frame = frameResource(frameId).takeIf { it != 0 } ?: return null
        val mark = markResource(markId).takeIf { it != 0 } ?: return null
        return ComposedBannerDrawable(
            requireNotNull(context.getDrawable(frame)).mutate(),
            requireNotNull(context.getDrawable(mark)).mutate(),
        )
    }

    @DrawableRes
    private fun frameResource(id: Long): Int = when (id) {
        1L -> R.drawable.guild_flag_bg_1
        2L -> R.drawable.guild_flag_bg_2
        3L -> R.drawable.guild_flag_bg_3
        4L -> R.drawable.guild_flag_bg_4
        5L -> R.drawable.guild_flag_bg_5
        6L -> R.drawable.guild_flag_bg_6
        else -> 0
    }

    @DrawableRes
    private fun markResource(id: Long): Int = when (id) {
        1L -> R.drawable.guild_flag_mark_1
        2L -> R.drawable.guild_flag_mark_2
        3L -> R.drawable.guild_flag_mark_3
        4L -> R.drawable.guild_flag_mark_4
        5L -> R.drawable.guild_flag_mark_5
        6L -> R.drawable.guild_flag_mark_6
        else -> 0
    }

    /**
     * The source frame canvas is 512 px and the source mark canvas is 400 px.
     * Retaining that ratio preserves the exact alignment used by the game UI.
     */
    private class ComposedBannerDrawable(
        private val frame: Drawable,
        private val mark: Drawable,
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            frame.draw(canvas)
            mark.draw(canvas)
        }

        override fun onBoundsChange(bounds: Rect) {
            val size = min(bounds.width(), bounds.height())
            val left = bounds.left + (bounds.width() - size) / 2
            val top = bounds.top + (bounds.height() - size) / 2
            frame.bounds = Rect(left, top, left + size, top + size)

            val markSize = (size * MARK_TO_FRAME_RATIO).roundToInt()
            val markLeft = bounds.left + (bounds.width() - markSize) / 2
            val markTop = bounds.top + (bounds.height() - markSize) / 2
            mark.bounds = Rect(markLeft, markTop, markLeft + markSize, markTop + markSize)
        }

        override fun setAlpha(alpha: Int) {
            frame.alpha = alpha
            mark.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            frame.colorFilter = colorFilter
            mark.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = SOURCE_FRAME_SIZE

        override fun getIntrinsicHeight(): Int = SOURCE_FRAME_SIZE
    }

    private const val SOURCE_FRAME_SIZE = 512
    private const val SOURCE_MARK_SIZE = 400
    private const val MARK_TO_FRAME_RATIO = SOURCE_MARK_SIZE.toFloat() / SOURCE_FRAME_SIZE
}
