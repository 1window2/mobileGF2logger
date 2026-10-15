package dev.gf2log.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.annotation.ColorRes
import dev.gf2log.app.R

/** Semantic control styling from docs/UI_DESIGN_SYSTEM.md; screens choose roles, never colors. */
internal object Controls {
    enum class Role {
        /** The single commitment action of a screen or panel. */
        PRIMARY,

        /** Starts continuous capture; green readiness treatment. */
        CAPTURE,
        SECONDARY,

        /** Low-emphasis inline action such as Select or Show all. */
        TERTIARY,
        DESTRUCTIVE,
        DESTRUCTIVE_TEXT,
    }

    private data class Palette(
        @param:ColorRes val fill: Int,
        @param:ColorRes val pressed: Int,
        @param:ColorRes val stroke: Int,
        @param:ColorRes val text: Int,
    )

    private fun palette(role: Role): Palette = when (role) {
        Role.PRIMARY -> Palette(
            R.color.primary_action_background,
            R.color.primary_action_pressed,
            android.R.color.transparent,
            R.color.primary_action_foreground,
        )
        Role.CAPTURE -> Palette(R.color.success_surface, R.color.success_pressed, R.color.success_text, R.color.success_text)
        Role.SECONDARY -> Palette(R.color.surface_variant, R.color.surface_pressed, android.R.color.transparent, R.color.text_primary)
        Role.TERTIARY -> Palette(android.R.color.transparent, R.color.surface_pressed, android.R.color.transparent, R.color.accent_text)
        Role.DESTRUCTIVE -> Palette(
            R.color.destructive_action_background,
            R.color.destructive_action_pressed,
            android.R.color.transparent,
            R.color.destructive_action_foreground,
        )
        Role.DESTRUCTIVE_TEXT -> Palette(
            android.R.color.transparent,
            R.color.surface_pressed,
            android.R.color.transparent,
            R.color.destructive_action,
        )
    }

    /** A 48dp touch target around a 40dp visible surface with explicit disabled state. */
    fun style(button: Button, role: Role) {
        val context = button.context
        val colors = palette(role)
        button.isAllCaps = false
        button.elevation = 0f
        button.stateListAnimator = null
        button.minHeight = context.dp(48)
        button.minWidth = context.dp(48)
        button.textSize = 14f
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.maxLines = 2
        button.ellipsize = TextUtils.TruncateAt.END
        button.gravity = Gravity.CENTER
        button.setPaddingRelative(context.dp(16), 0, context.dp(16), 0)
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(context.getColor(R.color.text_secondary), context.getColor(colors.text)),
            ),
        )
        button.backgroundTintList = null
        button.background = InsetDrawable(
            StateListDrawable().apply {
                val disabledFill = if (colors.fill == android.R.color.transparent) colors.fill else R.color.surface_variant
                addState(intArrayOf(-android.R.attr.state_enabled), shape(context, disabledFill, android.R.color.transparent))
                addState(intArrayOf(android.R.attr.state_pressed), shape(context, colors.pressed, colors.stroke))
                addState(intArrayOf(), shape(context, colors.fill, colors.stroke))
            },
            0,
            context.dp(4),
            0,
            context.dp(4),
        )
        button.refreshDrawableState()
        button.jumpDrawablesToCurrentState()
    }

    private fun shape(context: Context, @ColorRes fill: Int, @ColorRes stroke: Int): Drawable = GradientDrawable().apply {
        setColor(context.getColor(fill))
        cornerRadius = context.dpf(12f)
        if (stroke != android.R.color.transparent) setStroke(context.dp(1), context.getColor(stroke))
    }

    fun controlTint(context: Context) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(
            context.getColor(R.color.outline_strong),
            context.getColor(R.color.accent),
            context.getColor(R.color.outline_strong),
        ),
    )

    fun switchThumbTint(context: Context) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(
            context.getColor(R.color.surface_variant),
            context.getColor(R.color.accent),
            context.getColor(R.color.surface),
        ),
    )

    fun switchTrackTint(context: Context) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(
            context.getColor(R.color.outline),
            context.getColor(R.color.accent_surface_pressed),
            context.getColor(R.color.outline_strong),
        ),
    )

    /**
     * Mutually exclusive choices on one 56dp rail: a 48dp accent thumb glides between equal-width
     * native radio buttons, and motion is skipped when system animations are off.
     */
    fun segmentedControl(
        context: Context,
        options: List<Pair<String, CharSequence>>,
        selectedValue: String,
        onSelected: (String) -> Unit,
    ): View {
        require(options.isNotEmpty()) { "A segmented control requires at least one option" }
        val segmentHeight = context.dp(48)
        val selection = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.accent_surface))
                cornerRadius = context.dpf(12f)
            }
        }
        val choices = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val rail = FrameLayout(context).apply {
            val inset = context.dp(4)
            setPadding(inset, inset, inset, inset)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.surface_variant))
                cornerRadius = context.dpf(16f)
            }
            addView(selection, FrameLayout.LayoutParams(0, segmentHeight))
            addView(choices, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, segmentHeight))
        }
        var currentValue = selectedValue

        fun moveSelection(index: Int, animated: Boolean, finished: (() -> Unit)? = null) {
            val segment = choices.getChildAt(index)
            if (segment == null || segment.width <= 0) {
                rail.post { moveSelection(index, animated, finished) }
                return
            }
            selection.layoutParams = (selection.layoutParams as FrameLayout.LayoutParams).apply { width = segment.width }
            val target = segment.left.toFloat()
            selection.animate().cancel()
            if (animated && ValueAnimator.areAnimatorsEnabled()) {
                selection.animate().translationX(target).setDuration(160L).withEndAction { finished?.invoke() }.start()
            } else {
                selection.translationX = target
                finished?.invoke()
            }
        }

        val textColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(context.getColor(R.color.accent_text), context.getColor(R.color.text_primary)),
        )
        options.forEachIndexed { index, (value, label) ->
            choices.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                buttonDrawable = null
                background = RippleDrawable(
                    ColorStateList.valueOf(context.getColor(R.color.accent_surface_pressed)),
                    null,
                    GradientDrawable().apply {
                        setColor(context.getColor(android.R.color.white))
                        cornerRadius = context.dpf(12f)
                    },
                )
                setTextColor(textColors)
                isChecked = value == selectedValue
                minHeight = segmentHeight
                minWidth = context.dp(48)
                setPadding(context.dp(4), 0, context.dp(4), 0)
                setOnClickListener {
                    if (value == currentValue) return@setOnClickListener
                    currentValue = value
                    moveSelection(index, animated = true) { onSelected(value) }
                }
            }, RadioGroup.LayoutParams(0, segmentHeight, 1f))
        }
        rail.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            moveSelection(options.indexOfFirst { it.first == currentValue }.coerceAtLeast(0), animated = false)
        }
        return rail
    }
}
