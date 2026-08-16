package dev.gf2log.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/** Shared presentation primitives for the warm, editorial product surface. */
object ModernUi {
    internal enum class ControlRole {
        PRIMARY,
        CAPTURE,
        FEATURE,
        SEGMENT_SELECTED,
        SECONDARY,
        TERTIARY,
        NAVIGATION,
        DESTRUCTIVE,
        DESTRUCTIVE_TEXT,
    }

    fun prepareContent(root: View) {
        root.setBackgroundColor(root.context.getColor(R.color.app_background))
        prepareScreenSurface(root)
        applyControls(root)
    }

    fun panelBackground(context: Context, emphasized: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(context.getColor(if (emphasized) R.color.accent_surface else R.color.surface))
            cornerRadius = context.dp(16).toFloat()
        }

    /**
     * Creates a compact multi-line navigation row without presenting its copy as an oversized button.
     * The complete row remains a native 56dp touch target and exposes button semantics.
     */
    fun actionRow(
        context: Context,
        title: CharSequence,
        detail: CharSequence? = null,
        onClick: () -> Unit,
    ): View = AccessibleActionRow(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56)
        setPadding(context.dp(14), context.dp(6), context.dp(10), context.dp(6))
        background = statefulSurface(
            context = context,
            normalColor = R.color.surface,
            pressedColor = R.color.surface_pressed,
            disabledColor = R.color.surface_variant,
            normalStroke = android.R.color.transparent,
            pressedStroke = R.color.outline,
            radiusDp = 14,
        )
        contentDescription = listOfNotNull(title, detail?.takeIf(CharSequence::isNotBlank))
            .joinToString(". ")
        setOnClickListener { onClick() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(context.getColor(R.color.text_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            detail?.takeIf(CharSequence::isNotBlank)?.let { supportingText ->
                addView(TextView(context).apply {
                    text = supportingText
                    textSize = 13f
                    setTextColor(context.getColor(R.color.text_secondary))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, context.dp(2), 0, 0)
                }, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply {
            marginStart = context.dp(8)
        })
    }

    /** Compact mutually-exclusive choices used for language and theme settings. */
    fun segmentedControl(
        context: Context,
        options: List<Pair<String, CharSequence>>,
        selectedValue: String,
        onSelected: (String) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.surface_variant))
            cornerRadius = context.dp(14).toFloat()
        }
        options.forEach { (value, label) ->
            addView(Button(context).apply {
                text = label
                setTag(
                    R.id.gf2_ui_role,
                    if (value == selectedValue) ControlRole.SEGMENT_SELECTED else ControlRole.TERTIARY,
                )
                styleButton(this)
                setOnClickListener { onSelected(value) }
            }, LinearLayout.LayoutParams(0, context.dp(46), 1f))
        }
    }

    internal fun styleButton(button: Button) {
        val role = button.getTag(R.id.gf2_ui_role) as? ControlRole ?: ControlRole.SECONDARY
        button.isAllCaps = false
        button.elevation = 0f
        button.stateListAnimator = null
        button.minHeight = button.context.dp(48)
        button.minWidth = button.context.dp(48)
        val compactMultiline = button.getTag(R.id.gf2_ui_compact_multiline) == true
        button.textSize = when {
            compactMultiline -> 12f
            role == ControlRole.FEATURE -> 13f
            else -> 14f
        }
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.maxLines = if (role == ControlRole.FEATURE || compactMultiline) 2 else 1
        button.ellipsize = TextUtils.TruncateAt.END
        button.gravity = if (role == ControlRole.NAVIGATION || role == ControlRole.FEATURE) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER
        }
        button.setPaddingRelative(button.context.dp(12), 0, button.context.dp(12), 0)

        val colors = when (role) {
            ControlRole.PRIMARY -> ButtonColors(
                normal = R.color.primary_action_background,
                pressed = R.color.primary_action_pressed,
                disabled = R.color.surface_variant,
                stroke = R.color.primary_action_background,
                pressedStroke = R.color.primary_action_pressed,
                text = R.color.primary_action_foreground,
                insetVertical = 4,
                radiusDp = 12,
            )
            ControlRole.CAPTURE -> ButtonColors(
                normal = R.color.success_surface,
                pressed = R.color.success_pressed,
                disabled = R.color.surface_variant,
                stroke = R.color.success_text,
                pressedStroke = R.color.success_text,
                text = R.color.success_text,
                insetVertical = 4,
                radiusDp = 12,
            )
            ControlRole.FEATURE -> ButtonColors(
                normal = R.color.accent_surface,
                pressed = R.color.accent_surface_pressed,
                disabled = R.color.surface_variant,
                stroke = android.R.color.transparent,
                pressedStroke = R.color.accent,
                text = R.color.accent_text,
                insetVertical = 4,
                radiusDp = 14,
            )
            ControlRole.SEGMENT_SELECTED -> ButtonColors(
                normal = R.color.accent_surface,
                pressed = R.color.accent_surface_pressed,
                disabled = R.color.surface_variant,
                stroke = android.R.color.transparent,
                pressedStroke = R.color.accent,
                text = R.color.accent_text,
                insetVertical = 2,
                radiusDp = 11,
            )
            ControlRole.DESTRUCTIVE -> ButtonColors(
                normal = R.color.destructive_action_background,
                pressed = R.color.destructive_action_pressed,
                disabled = R.color.surface_variant,
                stroke = R.color.destructive_action_background,
                pressedStroke = R.color.destructive_action_pressed,
                text = R.color.destructive_action_foreground,
                insetVertical = 4,
                radiusDp = 12,
            )
            ControlRole.TERTIARY -> ButtonColors(
                normal = R.color.app_background,
                pressed = R.color.surface_pressed,
                disabled = R.color.app_background,
                stroke = android.R.color.transparent,
                pressedStroke = android.R.color.transparent,
                text = R.color.text_primary,
                insetVertical = 5,
            )
            ControlRole.DESTRUCTIVE_TEXT -> ButtonColors(
                normal = R.color.app_background,
                pressed = R.color.surface_pressed,
                disabled = R.color.app_background,
                stroke = android.R.color.transparent,
                pressedStroke = android.R.color.transparent,
                text = R.color.destructive_action,
                insetVertical = 5,
            )
            ControlRole.NAVIGATION -> ButtonColors(
                normal = R.color.surface,
                pressed = R.color.surface_pressed,
                disabled = R.color.surface_variant,
                stroke = android.R.color.transparent,
                pressedStroke = R.color.outline_strong,
                text = R.color.text_primary,
                insetVertical = 4,
                radiusDp = 12,
            )
            ControlRole.SECONDARY -> ButtonColors(
                normal = R.color.surface,
                pressed = R.color.surface_pressed,
                disabled = R.color.surface_variant,
                stroke = android.R.color.transparent,
                pressedStroke = R.color.outline_strong,
                text = R.color.text_primary,
                insetVertical = 4,
                radiusDp = 12,
            )
        }
        button.setTextColor(buttonTextColors(button.context, colors.text))
        button.backgroundTintList = null
        button.background = InsetDrawable(
            statefulSurface(
                context = button.context,
                normalColor = colors.normal,
                pressedColor = colors.pressed,
                disabledColor = colors.disabled,
                normalStroke = colors.stroke,
                pressedStroke = colors.pressedStroke,
                radiusDp = colors.radiusDp,
            ),
            0,
            button.context.dp(colors.insetVertical),
            0,
            button.context.dp(colors.insetVertical),
        )
        // A restored Button can keep the drawable state from its previous platform
        // background even though View.isEnabled is already true. Synchronize the new
        // semantic drawable immediately after replacing it.
        button.refreshDrawableState()
        button.jumpDrawablesToCurrentState()
    }

    private fun applyControls(view: View) {
        when (view) {
            is ScrollView -> view.isFillViewport = true
            is CheckBox -> {
                view.buttonTintList = controlTint(view.context)
                view.minimumHeight = view.context.dp(48)
            }
            is RadioButton -> {
                view.buttonTintList = controlTint(view.context)
                view.minimumHeight = view.context.dp(48)
            }
            is Button -> styleButton(view)
            is EditText -> {
                view.elevation = 0f
                view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            is ImageButton -> view.useModernIconStyle()
            is TextView -> styleText(view)
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> applyControls(view.getChildAt(index)) }
        }
    }

    private fun styleText(textView: TextView) {
        if (textView.typeface == Typeface.MONOSPACE) return
        val sizeSp = textView.textSize /
            (textView.resources.displayMetrics.density * textView.resources.configuration.fontScale)
        when {
            sizeSp >= 24f && textView.typeface?.isBold == true -> {
                textView.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textView.letterSpacing = -0.01f
            }
            sizeSp >= 18f && textView.typeface?.isBold == true -> {
                textView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textView.letterSpacing = 0f
            }
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

    private fun statefulSurface(
        context: Context,
        normalColor: Int,
        pressedColor: Int,
        disabledColor: Int,
        normalStroke: Int,
        pressedStroke: Int,
        radiusDp: Int,
    ): Drawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                shape(context, disabledColor, normalStroke, radiusDp),
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                shape(context, pressedColor, pressedStroke, radiusDp),
            )
            addState(intArrayOf(), shape(context, normalColor, normalStroke, radiusDp))
        }
    }

    private fun shape(
        context: Context,
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int,
    ) = GradientDrawable().apply {
        setColor(context.getColor(fillColor))
        cornerRadius = context.dp(radiusDp).toFloat()
        if (strokeColor != android.R.color.transparent) {
            setStroke(context.dp(1), context.getColor(strokeColor))
        }
    }

    private fun buttonTextColors(context: Context, enabledColor: Int) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(),
        ),
        intArrayOf(
            context.getColor(R.color.text_secondary),
            context.getColor(enabledColor),
        ),
    )

    private fun controlTint(context: Context) = ColorStateList(
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

    private data class ButtonColors(
        val normal: Int,
        val pressed: Int,
        val disabled: Int,
        val stroke: Int,
        val pressedStroke: Int,
        val text: Int,
        val insetVertical: Int,
        val radiusDp: Int = 2,
    )
}

private class AccessibleActionRow(context: Context) : LinearLayout(context) {
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun ImageButton.useModernIconStyle() {
    background = InsetDrawable(ModernUi.panelBackground(context), context.dp(4))
    imageTintList = ColorStateList.valueOf(context.getColor(R.color.primary))
    elevation = 0f
}
