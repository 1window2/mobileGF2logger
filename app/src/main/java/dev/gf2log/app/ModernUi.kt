package dev.gf2log.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
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
            cornerRadius = context.dp(22).toFloat()
            setStroke(
                context.dp(1),
                context.getColor(if (emphasized) R.color.primary else R.color.outline),
            )
        }

    private fun applyControls(view: View) {
        when (view) {
            is ScrollView -> view.isFillViewport = true
            is Button -> {
                view.isAllCaps = false
                view.elevation = 0f
                view.stateListAnimator = null
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
        if (screen !is ScrollView || screen.childCount == 0) return
        screen.isFillViewport = true
        screen.clipToPadding = false
        screen.setPadding(screen.context.dp(8), screen.context.dp(8), screen.context.dp(8), screen.context.dp(8))
        screen.getChildAt(0).apply {
            background = panelBackground(context)
            elevation = context.dp(1).toFloat()
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
            context.getColor(R.color.primary),
            context.getColor(R.color.outline_strong),
        ),
    )
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun ImageButton.useModernIconStyle() {
    background = ModernUi.panelBackground(context, emphasized = true)
    imageTintList = ColorStateList.valueOf(context.getColor(R.color.primary))
    elevation = 0f
}
