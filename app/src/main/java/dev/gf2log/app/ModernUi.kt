package dev.gf2log.app

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
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes

/** Shared presentation primitives for the warm, editorial product surface. */
object ModernUi {
    internal enum class ControlRole {
        PRIMARY,
        CAPTURE,
        FEATURE,
        EVIDENCE,
        SECONDARY,
        TERTIARY,
        NAVIGATION,
        SELECTOR,
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

    /** Theme-aware surface used by app-owned dialogs without replacing native semantics. */
    fun dialogBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(context.getColor(R.color.surface))
        cornerRadius = context.dp(20).toFloat()
        setStroke(context.dp(1), context.getColor(R.color.outline))
    }

    /** Flat list navigation used by dashboard utilities and grouped Settings rows. */
    fun listRow(
        context: Context,
        title: CharSequence,
        detail: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(AccessibleActionRow(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(if (detail.isNullOrBlank()) 52 else 60)
            setPadding(context.dp(8), context.dp(4), context.dp(4), context.dp(4))
            background = statefulSurface(
                context = context,
                normalColor = R.color.app_background,
                pressedColor = R.color.surface_pressed,
                disabledColor = R.color.app_background,
                normalStroke = android.R.color.transparent,
                pressedStroke = android.R.color.transparent,
                radiusDp = 2,
            )
            contentDescription = listOfNotNull(title, detail?.takeIf(CharSequence::isNotBlank))
                .joinToString(". ")
            setOnClickListener { onClick() }
            icon?.let { drawable ->
                addView(ImageView(context).apply {
                    setImageResource(drawable)
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                    marginEnd = context.dp(12)
                })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(TextView(context).apply {
                    text = title
                    textSize = 14f
                    setTextColor(context.getColor(R.color.text_primary))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                detail?.takeIf(CharSequence::isNotBlank)?.let { supportingText ->
                    addView(TextView(context).apply {
                        text = supportingText
                        textSize = 12f
                        setTextColor(context.getColor(R.color.text_secondary))
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply {
                marginStart = context.dp(8)
            })
        }, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.outline))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            marginStart = context.dp(if (icon == null) 8 else 44)
        })
    }

    /**
     * Creates a compact multi-line navigation row without presenting its copy as an oversized button.
     * The complete row remains a native 56dp touch target and exposes button semantics.
     */
    fun actionRow(
        context: Context,
        title: CharSequence,
        detail: CharSequence? = null,
        titleMaxLines: Int = 1,
        onClick: () -> Unit,
    ): View = AccessibleActionRow(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56)
        setPadding(context.dp(12), context.dp(8), context.dp(8), context.dp(8))
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
                maxLines = titleMaxLines
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
                    setPadding(0, context.dp(4), 0, 0)
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
    ): View {
        require(options.isNotEmpty()) { "A segmented control requires at least one option" }
        val selectedTextColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(
                context.getColor(R.color.accent_text),
                context.getColor(R.color.text_primary),
            ),
        )
        val railInset = context.dp(4)
        val segmentHeight = context.dp(40)
        val selection = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.accent_surface))
                cornerRadius = context.dp(10).toFloat()
            }
        }
        val choices = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            background = null
        }
        val rail = FrameLayout(context).apply {
            minimumHeight = context.dp(48)
            setPadding(railInset, railInset, railInset, railInset)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.surface_variant))
                cornerRadius = context.dp(14).toFloat()
            }
            addView(selection, FrameLayout.LayoutParams(0, segmentHeight))
            addView(
                choices,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    segmentHeight,
                ),
            )
        }

        var currentValue = selectedValue
        fun moveSelection(index: Int, animated: Boolean, finished: (() -> Unit)? = null) {
            val segment = choices.getChildAt(index)
            if (segment == null || segment.width <= 0) {
                rail.post { moveSelection(index, animated, finished) }
                return
            }
            selection.layoutParams = (selection.layoutParams as FrameLayout.LayoutParams).apply {
                width = segment.width
                height = segmentHeight
            }
            val target = segment.left.toFloat()
            selection.animate().cancel()
            if (animated) {
                choices.isEnabled = false
                repeat(choices.childCount) { child -> choices.getChildAt(child).isEnabled = false }
                selection.animate()
                    .translationX(target)
                    .setDuration(180L)
                    .withEndAction {
                        choices.isEnabled = true
                        repeat(choices.childCount) { child -> choices.getChildAt(child).isEnabled = true }
                        finished?.invoke()
                    }
                    .start()
            } else {
                selection.translationX = target
                finished?.invoke()
            }
        }

        options.forEachIndexed { index, (value, label) ->
            choices.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                buttonDrawable = null
                background = RippleDrawable(
                    ColorStateList.valueOf(context.getColor(R.color.accent_surface_pressed)),
                    null,
                    shape(
                        context,
                        android.R.color.white,
                        android.R.color.transparent,
                        10,
                    ),
                )
                setTextColor(selectedTextColors)
                isChecked = value == selectedValue
                minHeight = segmentHeight
                minWidth = context.dp(48)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    if (value == currentValue) return@setOnClickListener
                    currentValue = value
                    moveSelection(index, animated = true) { onSelected(value) }
                }
            }, RadioGroup.LayoutParams(0, segmentHeight, 1f))
        }
        rail.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val selectedIndex = options.indexOfFirst { it.first == currentValue }.coerceAtLeast(0)
            moveSelection(selectedIndex, animated = false)
        }
        return rail
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
            role == ControlRole.EVIDENCE -> 12f
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
            ControlRole.EVIDENCE -> ButtonColors(
                normal = R.color.surface,
                pressed = R.color.surface_pressed,
                disabled = R.color.surface_variant,
                stroke = android.R.color.transparent,
                pressedStroke = R.color.outline_strong,
                text = R.color.text_primary,
                insetVertical = 8,
                radiusDp = 10,
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
            ControlRole.SELECTOR -> ButtonColors(
                normal = R.color.surface,
                pressed = R.color.surface_pressed,
                disabled = R.color.surface_variant,
                stroke = R.color.outline_strong,
                pressedStroke = R.color.accent,
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
                view.minimumWidth = view.context.dp(48)
            }
            is RadioButton -> {
                view.buttonTintList = controlTint(view.context)
                view.minimumHeight = view.context.dp(48)
                view.minimumWidth = view.context.dp(48)
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
