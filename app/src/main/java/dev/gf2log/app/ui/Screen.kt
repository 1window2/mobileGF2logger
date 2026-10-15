package dev.gf2log.app.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import dev.gf2log.app.PrimaryNavigation
import dev.gf2log.app.R

/**
 * Edge-to-edge screen shell: top bar, replaceable body, and the optional three-destination
 * bottom bar. Window insets are applied here once so no Activity handles status, navigation,
 * cutout, or keyboard insets on its own.
 */
internal class Screen(
    private val activity: Activity,
    destination: PrimaryNavigation.Destination? = null,
) {
    val topBar = TopBar(activity)
    private val content = FrameLayout(activity)
    private val bottomBar = destination?.let { PrimaryNavigation.bar(activity, it) }
    private var bottomInset = 0
    private var body: View? = null

    val root: LinearLayout = activity.column {
        setBackgroundColor(activity.getColor(R.color.app_background))
        addView(topBar, activity.lp())
        addView(content, activity.lp(height = 0, weight = 1f))
        bottomBar?.let {
            addView(activity.divider())
            addView(it, activity.lp())
        }
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, 0, bars.right, 0)
            topBar.setPadding(topBar.paddingLeft, bars.top, topBar.paddingRight, 0)
            bottomInset = if (bottomBar != null) {
                bottomBar.setPadding(0, 0, 0, bars.bottom)
                maxOf(0, ime.bottom - bars.bottom)
            } else {
                maxOf(bars.bottom, ime.bottom)
            }
            applyBottomInset()
            WindowInsetsCompat.CONSUMED
        }
        activity.setContentView(root)
    }

    /** Replaces the body; scrolling bodies draw beneath the navigation bar and pad their end. */
    fun setBody(view: View) {
        content.removeAllViews()
        body = view
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        applyBottomInset()
    }

    private fun applyBottomInset() {
        val target = body ?: return
        val scrolls = target is ScrollView || target is RecyclerView || target is HorizontalScrollView
        val padded = if (scrolls) target else content
        val base = (padded.getTag(R.id.gf2_base_bottom_padding) as? Int)
            ?: padded.paddingBottom.also { padded.setTag(R.id.gf2_base_bottom_padding, it) }
        padded.setPadding(padded.paddingLeft, padded.paddingTop, padded.paddingRight, base + bottomInset)
        padded.clipToPadding = false
    }

    companion object {
        /** Standard vertically scrolling body with the 16dp screen inset from DESIGN.md. */
        fun scrollingBody(activity: Activity, column: LinearLayout): ScrollView =
            ScrollView(activity).apply {
                id = R.id.screen_scroll
                isFillViewport = true
                column.setPadding(
                    activity.dp(16),
                    activity.dp(4),
                    activity.dp(16),
                    activity.dp(24),
                )
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            }
    }
}
