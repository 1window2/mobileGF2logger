package dev.gf2log.app.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
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
        textSize = 19f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        accessibilityHeading()
    }
    private val subtitleView = context.text(null, TextRole.CAPTION) {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val actions = context.row()

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

    val title: TextView get() = titleView

    fun setTitle(value: CharSequence?, subtitle: CharSequence? = null): TopBar = apply {
        titleView.text = value
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
    }

    fun setLeading(view: View?): TopBar = apply {
        leading.removeAllViews()
        leading.visibility = if (view == null) View.GONE else View.VISIBLE
        view?.let { leading.addView(it) }
    }

    fun setActions(items: List<BarAction>, overflow: List<MenuEntry> = emptyList()): TopBar = apply {
        actions.removeAllViews()
        items.forEach { action ->
            actions.addView(context.iconButton(action.icon, action.label, onClick = action.onClick).apply {
                isEnabled = action.enabled
            }, context.lp(48, 48))
        }
        if (overflow.isNotEmpty()) {
            actions.addView(
                context.iconButton(R.drawable.ic_more_vert, context.getString(R.string.more_options)) { anchor ->
                    showMenu(anchor, overflow)
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
