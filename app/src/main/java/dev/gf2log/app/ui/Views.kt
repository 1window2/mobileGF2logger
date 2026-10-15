package dev.gf2log.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
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
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import dev.gf2log.app.R

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

/** Linear layout parameters expressed in dp; MATCH and WRAP pass through unchanged. */
internal fun Context.lp(
    width: Int = MATCH,
    height: Int = WRAP,
    weight: Float = 0f,
    top: Int = 0,
    bottom: Int = 0,
    start: Int = 0,
    end: Int = 0,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    if (width < 0) width else dp(width),
    if (height < 0) height else dp(height),
    weight,
).apply {
    topMargin = dp(top)
    bottomMargin = dp(bottom)
    marginStart = dp(start)
    marginEnd = dp(end)
}

internal fun Context.column(
    padding: Int = 0,
    block: LinearLayout.() -> Unit = {},
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (padding > 0) setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    block()
}

internal fun Context.row(
    gravity: Int = Gravity.CENTER_VERTICAL,
    block: LinearLayout.() -> Unit = {},
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
    block()
}

/** Typography roles from DESIGN.md; sizes follow the user's font scale. */
internal enum class TextRole(
    val sizeSp: Float,
    val family: String,
    val style: Int,
    @ColorRes val color: Int,
) {
    HEADLINE(22f, "sans-serif", Typeface.BOLD, R.color.text_primary),
    TITLE(17f, "sans-serif-medium", Typeface.NORMAL, R.color.text_primary),
    SUBTITLE(15f, "sans-serif-medium", Typeface.NORMAL, R.color.text_primary),
    BODY(14f, "sans-serif", Typeface.NORMAL, R.color.text_primary),
    SECONDARY(13f, "sans-serif", Typeface.NORMAL, R.color.text_secondary),
    LABEL(12f, "sans-serif-medium", Typeface.NORMAL, R.color.text_secondary),
    CAPTION(12f, "sans-serif", Typeface.NORMAL, R.color.text_secondary),
}

internal fun Context.text(
    value: CharSequence?,
    role: TextRole = TextRole.BODY,
    block: TextView.() -> Unit = {},
): TextView = TextView(this).apply {
    text = value
    applyRole(role)
    block()
}

internal fun TextView.applyRole(role: TextRole) {
    textSize = role.sizeSp
    typeface = Typeface.create(role.family, role.style)
    setTextColor(context.getColor(role.color))
    setLineSpacing(0f, if (role == TextRole.HEADLINE) 1.05f else 1.15f)
}

/** Section label used above grouped rows. */
internal fun Context.sectionHeader(value: CharSequence): TextView = text(value, TextRole.LABEL) {
    isAllCaps = false
    setTextColor(getColor(R.color.accent_text))
    setPadding(dp(4), dp(20), dp(4), dp(6))
    isFocusable = false
    accessibilityHeading()
}

internal fun View.accessibilityHeading() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        isAccessibilityHeading = true
    }
}

internal fun Context.button(
    label: CharSequence,
    role: Controls.Role = Controls.Role.SECONDARY,
    @DrawableRes icon: Int? = null,
    onClick: (View) -> Unit,
): Button = Button(this).apply {
    text = label
    Controls.style(this, role)
    icon?.let { drawable ->
        val image = requireNotNull(getDrawable(drawable)).mutate()
        image.setTint(currentTextColor)
        val size = dp(18)
        image.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(image, null, null, null)
        compoundDrawablePadding = dp(8)
    }
    setOnClickListener(onClick)
}

internal fun Context.iconButton(
    @DrawableRes icon: Int,
    description: CharSequence,
    @ColorRes tint: Int = R.color.text_primary,
    onClick: (View) -> Unit,
): ImageButton = ImageButton(this).apply {
    setImageResource(icon)
    contentDescription = description
    imageTintList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(getColor(R.color.outline_strong), getColor(tint)),
    )
    background = selectableBorderless()
    scaleType = ImageView.ScaleType.CENTER
    minimumWidth = dp(48)
    minimumHeight = dp(48)
    tooltipText = description
    setOnClickListener(onClick)
}

internal fun Context.icon(
    @DrawableRes icon: Int,
    @ColorRes tint: Int = R.color.text_secondary,
    sizeDp: Int = 24,
): ImageView = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(getColor(tint))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

internal fun Context.textField(
    hintText: CharSequence,
    @IdRes id: Int = View.NO_ID,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    singleLine: Boolean = true,
    maxLength: Int? = null,
): EditText = EditText(this).apply {
    if (id != View.NO_ID) this.id = id
    hint = hintText
    textSize = 15f
    this.inputType = if (singleLine) inputType else inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    if (singleLine) setSingleLine(true) else minLines = 2
    maxLength?.let { filters = arrayOf(InputFilter.LengthFilter(it)) }
    elevation = 0f
}

/** Labeled input with an optional supporting or error line. */
internal class LabeledField(
    context: Context,
    label: CharSequence,
    val input: EditText,
    private val supporting: CharSequence? = null,
) : LinearLayout(context) {
    private val message = context.text(supporting, TextRole.CAPTION) {
        setPadding(context.dp(4), context.dp(4), context.dp(4), 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = if (supporting.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    init {
        orientation = VERTICAL
        addView(context.text(label, TextRole.LABEL) {
            setPadding(context.dp(4), 0, context.dp(4), context.dp(4))
            labelFor = input.id.takeIf { it != View.NO_ID } ?: View.generateViewId().also { input.id = it }
        }, context.lp())
        addView(input, context.lp())
        addView(message, context.lp())
    }

    fun showError(error: CharSequence?) {
        message.text = error ?: supporting
        message.setTextColor(
            context.getColor(if (error == null) R.color.text_secondary else R.color.destructive_action),
        )
        message.visibility = if (message.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}

internal fun Context.checkBox(
    label: CharSequence,
    checked: Boolean,
    onChanged: (Boolean) -> Unit = {},
): CheckBox = CheckBox(this).apply {
    text = label
    isChecked = checked
    textSize = 14f
    setTextColor(getColor(R.color.text_primary))
    buttonTintList = Controls.controlTint(this@checkBox)
    minimumHeight = dp(48)
    setOnCheckedChangeListener { _, value -> onChanged(value) }
}

/** Full-row switch: the complete row toggles and exposes one accessible control. */
internal fun Context.switchRow(
    title: CharSequence,
    detail: CharSequence?,
    checked: Boolean,
    enabled: Boolean = true,
    badge: CharSequence? = null,
    onChanged: (Boolean) -> Unit,
): View {
    val toggle = Switch(this).apply {
        isChecked = checked
        isEnabled = enabled
        thumbTintList = Controls.switchThumbTint(this@switchRow)
        trackTintList = Controls.switchTrackTint(this@switchRow)
        contentDescription = listOfNotNull(title, badge, detail).joinToString(". ")
        setOnCheckedChangeListener { _, value -> onChanged(value) }
    }
    return row {
        minimumHeight = dp(if (detail.isNullOrBlank()) 52 else 64)
        setPadding(dp(4), dp(8), 0, dp(8))
        background = selectableRect()
        isEnabled = enabled
        if (enabled) setOnClickListener { toggle.toggle() }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(column {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(row {
                addView(text(title, TextRole.BODY) {
                    if (!enabled) setTextColor(getColor(R.color.text_secondary))
                }, lp(width = 0, weight = 1f))
                badge?.let { addView(badge(it), lp(width = WRAP, start = 8)) }
            }, lp())
            detail?.takeIf(CharSequence::isNotBlank)?.let {
                addView(text(it, TextRole.CAPTION) { setPadding(0, dp(2), 0, 0) }, lp())
            }
        }, lp(width = 0, weight = 1f))
        // A permanently required option shows its badge instead of an inert switch.
        if (enabled || badge == null) addView(toggle, lp(width = WRAP, start = 12))
    }
}

/** Compact non-interactive status or category label. */
internal fun Context.badge(
    value: CharSequence,
    @ColorRes background: Int = R.color.surface_variant,
    @ColorRes foreground: Int = R.color.text_secondary,
): TextView = text(value, TextRole.LABEL) {
    setTextColor(getColor(foreground))
    textSize = 11f
    gravity = Gravity.CENTER
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    setPadding(dp(8), dp(3), dp(8), dp(3))
    this.background = GradientDrawable().apply {
        setColor(getColor(background))
        cornerRadius = dpf(8f)
    }
}

internal fun Context.divider(insetStartDp: Int = 0): View = View(this).apply {
    setBackgroundColor(getColor(R.color.outline))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1))).apply {
        marginStart = dp(insetStartDp)
    }
}

/** Bounded tonal group for related rows; DESIGN.md "one tonal step" surface. */
internal fun Context.panel(
    emphasized: Boolean = false,
    outlined: Boolean = false,
    padding: Int = 16,
    block: LinearLayout.() -> Unit = {},
): LinearLayout = column {
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    background = GradientDrawable().apply {
        setColor(getColor(if (emphasized) R.color.accent_surface else R.color.surface))
        cornerRadius = dpf(16f)
        if (outlined) setStroke(dp(1), getColor(R.color.outline))
    }
    block()
}

/**
 * Grouped list container: rows are separated by inset hairlines and share one rounded surface,
 * matching the Settings composition in PRODUCT.md.
 */
internal fun Context.group(rows: List<View>): LinearLayout = column {
    background = GradientDrawable().apply {
        setColor(getColor(R.color.surface))
        cornerRadius = dpf(16f)
    }
    clipToOutline = true
    setPadding(dp(8), dp(4), dp(8), dp(4))
    rows.forEachIndexed { index, view ->
        if (index > 0) addView(divider(insetStartDp = 8))
        addView(view, lp())
    }
}

/** A tappable grouped row with an optional icon, supporting text, and trailing value. */
internal fun Context.navigationRow(
    title: CharSequence,
    detail: CharSequence? = null,
    @DrawableRes icon: Int? = null,
    trailing: CharSequence? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: () -> Unit,
): View = AccessibleRow(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(if (detail.isNullOrBlank()) 52 else 64)
    setPadding(dp(8), dp(8), dp(4), dp(8))
    background = selectableRect()
    isEnabled = enabled
    contentDescription = listOfNotNull(title, trailing, detail?.takeIf(CharSequence::isNotBlank))
        .joinToString(". ")
    setOnClickListener { onClick() }
    val primaryColor = when {
        !enabled -> R.color.text_secondary
        destructive -> R.color.destructive_action
        else -> R.color.text_primary
    }
    icon?.let {
        addView(icon(it, if (destructive) R.color.destructive_action else R.color.text_secondary), lp(
            width = 24,
            height = 24,
            end = 16,
        ))
    }
    addView(column {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(text(title, TextRole.BODY) {
            setTextColor(getColor(primaryColor))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lp())
        detail?.takeIf(CharSequence::isNotBlank)?.let {
            addView(text(it, TextRole.CAPTION) { setPadding(0, dp(2), 0, 0) }, lp())
        }
    }, lp(width = 0, weight = 1f))
    trailing?.let {
        addView(text(it, TextRole.SECONDARY) {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(160)
        }, lp(width = WRAP, start = 8))
    }
    if (showChevron) addView(icon(R.drawable.ic_chevron_right, sizeDp = 20), lp(20, 20, start = 4))
}

/** Exposes a composite row to accessibility services as one button. */
internal class AccessibleRow(context: Context) : LinearLayout(context) {
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

internal fun Context.selectableRect(): android.graphics.drawable.Drawable =
    android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(getColor(R.color.control_ripple)),
        null,
        GradientDrawable().apply {
            setColor(getColor(R.color.surface))
            cornerRadius = dpf(12f)
        },
    )

internal fun Context.selectableBorderless(): android.graphics.drawable.Drawable =
    android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(getColor(R.color.control_ripple)),
        null,
        null,
    )

internal fun Context.spacer(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

internal fun Context.frame(block: FrameLayout.() -> Unit = {}): FrameLayout =
    FrameLayout(this).apply(block)
