package dev.gf2log.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
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
        addView(context.text(message, TextRole.SECONDARY) {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(12), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, context.lp())
    }

    fun empty(
        context: Context,
        @DrawableRes icon: Int,
        title: CharSequence,
        detail: CharSequence? = null,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ): View = context.column {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(40), context.dp(24), context.dp(40))
        addView(context.icon(icon, R.color.outline_strong, sizeDp = 40), context.lp(40, 40))
        addView(context.text(title, TextRole.SUBTITLE) {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(12), 0, 0)
        }, context.lp())
        detail?.let {
            addView(context.text(it, TextRole.SECONDARY) {
                gravity = Gravity.CENTER
                setPadding(0, context.dp(6), 0, 0)
            }, context.lp())
        }
        if (actionLabel != null && onAction != null) {
            addView(
                context.button(actionLabel, Controls.Role.SECONDARY) { onAction() },
                context.lp(WRAP, WRAP, top = 16),
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
