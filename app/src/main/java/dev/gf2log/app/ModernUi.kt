package dev.gf2log.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.ScrollView

/** Shared presentation primitives for the modern, minimal UI surface. */
object ModernUi {
    fun prepareContent(root: View) {
        root.setBackgroundColor(root.context.getColor(R.color.app_background))
        prepareScreenSurface(root)
        applyControls(root)
    }

    fun panelBackground(context: Context, emphasized: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(context.getColor(if (emphasized) R.color.primary_soft else R.color.surface))
            cornerRadius = context.dp(10).toFloat()
            setStroke(
                context.dp(1),
                context.getColor(if (emphasized) R.color.outline_strong else R.color.outline),
            )
        }

    private fun applyControls(view: View) {
        when (view) {
            is ScrollView -> view.isFillViewport = true
            is Button -> {
                view.isAllCaps = false
                view.elevation = 0f
                view.stateListAnimator = null
                view.background?.let { drawable ->
                    view.background = InsetDrawable(
                        drawable,
                        view.context.dp(3),
                        view.context.dp(7),
                        view.context.dp(3),
                        view.context.dp(7),
                    )
                }
            }
            is EditText -> view.elevation = 0f
            is ImageButton -> {
                view.useModernIconStyle()
            }
            is CheckBox -> view.buttonTintList = controlTint(view.context)
            is RadioButton -> view.buttonTintList = controlTint(view.context)
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> applyControls(view.getChildAt(index)) }
        }
    }

    private fun prepareScreenSurface(root: View) {
        val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return
        val screen = content.getChildAt(0) ?: return
        screen.setBackgroundColor(screen.context.getColor(R.color.app_background))
        if (screen is ScrollView) {
            screen.isFillViewport = true
            screen.clipToPadding = false
        }
    }

    private fun controlTint(context: Context) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(
            context.getColor(R.color.outline),
            context.getColor(R.color.accent),
            context.getColor(R.color.outline_strong),
        ),
    )
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun ImageButton.useModernIconStyle() {
    background = InsetDrawable(ModernUi.panelBackground(context), context.dp(6))
    imageTintList = ColorStateList.valueOf(context.getColor(R.color.primary))
    elevation = 0f
}
