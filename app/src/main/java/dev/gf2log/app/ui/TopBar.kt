package dev.gf2log.app.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.annotation.DrawableRes
import dev.gf2log.app.R

/** One trailing top-bar control. Icon-only actions always carry an accessible label. */
internal data class BarAction(
    @DrawableRes val icon: Int,
    val label: CharSequence,
    val enabled: Boolean = true,
    val onClick: (View) -> Unit,
)

/** One overflow-menu entry; overflow keeps secondary utilities quiet. */
internal data class MenuEntry(
    val label: CharSequence,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Compact app bar shared by every screen. The status-bar inset is applied by [Screen]; this view
 * owns only its 56dp content row.
 */
internal class TopBar(context: Context) : LinearLayout(context) {
    private val navigation = context.iconButton(R.drawable.ic_arrow_back, context.getString(R.string.back)) {}
    private val leading = context.frame()
    private val titleView = context.text(null, TextRole.TITLE) {
        textSize = 20f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        accessibilityHeading()
        // A title that changes on a showing screen is state, such as "3 selected"; it is spoken.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val subtitleView = context.text(null, TextRole.CAPTION) {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val actions = context.row()
    private var shownActions = emptyList<BarAction>()
    private var shownOverflow = emptyList<MenuEntry>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56)
        setPadding(context.dp(4), 0, context.dp(4), 0)
        setBackgroundColor(context.getColor(R.color.app_background))
        navigation.visibility = View.GONE
        leading.visibility = View.GONE
        addView(navigation, context.lp(48, 48))
        addView(leading, context.lp(WRAP, WRAP, start = 8, end = 4))
        addView(context.column {
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, context.lp())
            addView(subtitleView, context.lp())
        }, context.lp(width = 0, weight = 1f, start = 8, end = 4))
        addView(actions, context.lp(WRAP, WRAP))
    }

    fun setTitle(value: CharSequence?, subtitle: CharSequence? = null): TopBar = apply {
        // Re-applying the same title must not announce it again.
        if (!TextUtils.equals(titleView.text, value)) titleView.text = value
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun setNavigation(onBack: (() -> Unit)?): TopBar = apply {
        navigation.visibility = if (onBack == null) View.GONE else View.VISIBLE
        navigation.setOnClickListener { onBack?.invoke() }
    }

    fun setNavigationIcon(@DrawableRes icon: Int, description: CharSequence): TopBar = apply {
        navigation.setImageResource(icon)
        navigation.contentDescription = description
        navigation.tooltipText = description
    }

    fun setLeading(view: View?): TopBar = apply {
        leading.removeAllViews()
        leading.visibility = if (view == null) View.GONE else View.VISIBLE
        view?.let { leading.addView(it) }
    }

    /**
     * Shows [items] and the [overflow] menu. Re-applying the same controls, such as after Select
     * all, updates the existing buttons in place, so the button a screen reader is focused on
     * stays in the bar instead of being replaced.
     */
    fun setActions(items: List<BarAction>, overflow: List<MenuEntry> = emptyList()): TopBar = apply {
        val reuse = sameControls(shownActions, items) && shownOverflow.isEmpty() == overflow.isEmpty()
        shownActions = items
        shownOverflow = overflow
        if (reuse) {
            items.forEachIndexed { index, action ->
                actions.getChildAt(index).apply {
                    isEnabled = action.enabled
                    setOnClickListener(action.onClick)
                }
            }
            return@apply
        }
        actions.removeAllViews()
        items.forEach { action ->
            actions.addView(context.iconButton(action.icon, action.label, onClick = action.onClick).apply {
                isEnabled = action.enabled
            }, context.lp(48, 48))
        }
        if (overflow.isNotEmpty()) {
            actions.addView(
                // Reads the latest entries, so an in-place update also refreshes the menu.
                context.iconButton(R.drawable.ic_more_vert, context.getString(R.string.more_options)) { anchor ->
                    showMenu(anchor, shownOverflow)
                },
                context.lp(48, 48),
            )
        }
    }

    private fun showMenu(anchor: View, entries: List<MenuEntry>) {
        PopupMenu(context, anchor, Gravity.END).apply {
            entries.forEachIndexed { index, entry ->
                menu.add(0, index, index, entry.label).isEnabled = entry.enabled
            }
            setOnMenuItemClickListener { item ->
                entries.getOrNull(item.itemId)?.onClick?.invoke()
                true
            }
        }.show()
    }
}

/** Whether [next] shows the same buttons as [current]: same icons and labels, in order. */
internal fun sameControls(current: List<BarAction>, next: List<BarAction>): Boolean =
    current.size == next.size && current.zip(next).all { (shown, wanted) ->
        shown.icon == wanted.icon && shown.label.toString() == wanted.label.toString()
    }
