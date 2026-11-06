package dev.gf2log.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import dev.gf2log.app.R

/** Loading, empty, and failure states share one centered composition on every screen. */
internal object StateViews {
    fun loading(context: Context, message: CharSequence): View = context.column {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(48), context.dp(24), context.dp(48))
        addView(ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, context.lp(36, 36))
        addView(context.text(null, TextRole.SECONDARY) {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(12), 0, 0)
            announceWhenAttached(message)
        }, context.lp())
    }

    /** One way out of a state view, styled by its [role] like any other control. */
    class Action(val label: CharSequence, val role: Controls.Role, val onClick: () -> Unit)

    fun empty(
        context: Context,
        @DrawableRes icon: Int,
        title: CharSequence,
        detail: CharSequence? = null,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ): View = blocked(
        context,
        icon,
        title,
        detail,
        listOfNotNull(
            if (actionLabel != null && onAction != null) Action(actionLabel, Controls.Role.SECONDARY, onAction) else null,
        ),
    )

    /**
     * The same composition for a state the user resolves before the screen can show its data,
     * such as a damaged database, with its [actions] stacked in the order given.
     */
    fun blocked(
        context: Context,
        @DrawableRes icon: Int,
        title: CharSequence,
        detail: CharSequence?,
        actions: List<Action>,
    ): View = context.column {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(40), context.dp(24), context.dp(40))
        addView(context.icon(icon, R.color.outline_strong, sizeDp = 40), context.lp(40, 40))
        addView(context.text(null, TextRole.SUBTITLE) {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(12), 0, 0)
            announceWhenAttached(title)
        }, context.lp())
        detail?.let {
            addView(context.text(it, TextRole.SECONDARY) {
                gravity = Gravity.CENTER
                setPadding(0, context.dp(6), 0, 0)
            }, context.lp())
        }
        actions.forEachIndexed { index, action ->
            addView(
                context.button(action.label, action.role) { action.onClick() },
                context.lp(WRAP, WRAP, top = if (index == 0) 16 else 8),
            )
        }
    }

    fun failure(context: Context, message: CharSequence, onRetry: () -> Unit): View = empty(
        context,
        R.drawable.ic_warning,
        message,
        actionLabel = context.getString(R.string.retry),
        onAction = onRetry,
    )
}

/**
 * A live region announces changes, not its own arrival, and a state view replaces the previous
 * one as a new view. The message is therefore set just after the view is attached, so screen
 * readers hear that a screen finished loading, came back empty, or failed.
 */
private fun TextView.announceWhenAttached(message: CharSequence) {
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.removeOnAttachStateChangeListener(this)
            view.post { text = message }
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    })
}
