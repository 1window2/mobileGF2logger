package dev.gf2log.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.gf2log.app.management.MembershipBoundaryValue
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.dpf
import dev.gf2log.app.ui.iconButton
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.text
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Calendar input with an optional clock value.
 *
 * Dates are stored explicitly so a date-only manual boundary remains stable if the device
 * timezone later changes. A date-only value uses local midnight as its representative Instant,
 * while [MembershipBoundaryValue.timeKnown] preserves that the time itself is unknown.
 */
internal class DateTimePickerInput(
    context: Context,
    label: String,
    initialValue: Instant? = null,
    initialDate: LocalDate? = null,
    initialTimeKnown: Boolean = initialValue != null,
    private val dateRequired: Boolean = false,
    private val editable: Boolean = true,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : LinearLayout(context) {
    private val selection = MembershipBoundaryDraft(
        initialValue = initialValue,
        initialDate = initialDate,
        initialTimeKnown = initialTimeKnown,
        zone = zone,
    )

    val boundary: MembershipBoundaryValue?
        get() = selection.boundary

    private val dateField = pickerField(R.drawable.ic_today) { showDatePicker() }
    private val timeField = pickerField(R.drawable.ic_schedule) { showTimePicker() }
    private val clearButton = context.iconButton(R.drawable.ic_close, context.getString(R.string.clear_date)) {
        if (selection.time != null) selection.clearTime() else selection.clearDate()
        renderValue()
    }

    init {
        orientation = VERTICAL
        addView(context.text(label, TextRole.LABEL) {
            setPadding(context.dp(4), 0, context.dp(4), context.dp(4))
        }, context.lp())
        addView(context.row {
            addView(dateField, context.lp(width = 0, weight = 1.3f))
            addView(timeField, context.lp(width = 0, weight = 1f, start = 8))
            addView(clearButton, context.lp(48, 48, start = 4))
        }, context.lp())
        if (!editable) {
            addView(context.text(context.getString(R.string.exact_boundary_read_only), TextRole.CAPTION) {
                setPadding(context.dp(4), context.dp(4), context.dp(4), 0)
            }, context.lp())
        }
        renderValue()
    }

    private fun pickerField(icon: Int, onClick: () -> Unit): TextView = context.text(null, TextRole.BODY) {
        gravity = Gravity.CENTER_VERTICAL
        // Grows with the font size instead of clipping; 48dp stays the minimum touch target.
        minHeight = context.dp(48)
        setPadding(context.dp(12), context.dp(6), context.dp(8), context.dp(6))
        maxLines = 1
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.surface_variant))
            cornerRadius = context.dpf(12f)
        }
        val image = requireNotNull(context.getDrawable(icon)).mutate()
        image.setTint(context.getColor(R.color.text_secondary))
        val size = context.dp(18)
        image.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(image, null, null, null)
        compoundDrawablePadding = context.dp(8)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun showDatePicker() {
        val seed = selection.date ?: LocalDate.now(zone)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                selection.selectDate(LocalDate.of(year, month + 1, day))
                renderValue()
            },
            seed.year,
            seed.monthValue - 1,
            seed.dayOfMonth,
        ).show()
    }

    private fun showTimePicker() {
        val seed = selection.time ?: LocalTime.now(zone).withSecond(0).withNano(0)
        TimePickerDialog(
            context,
            { _, hour, minute ->
                selection.selectTime(LocalTime.of(hour, minute))
                renderValue()
            },
            seed.hour,
            seed.minute,
            android.text.format.DateFormat.is24HourFormat(context),
        ).show()
    }

    private fun renderValue() {
        val date = selection.date
        val time = selection.time
        dateField.text = date?.format(Labels.DATE) ?: context.getString(R.string.date_unset)
        dateField.setTextColor(context.getColor(if (date == null) R.color.text_secondary else R.color.text_primary))
        timeField.text = time?.format(Labels.TIME) ?: context.getString(R.string.time_optional)
        timeField.setTextColor(context.getColor(if (time == null) R.color.text_secondary else R.color.text_primary))
        dateField.isEnabled = editable
        timeField.isEnabled = editable && date != null
        timeField.alpha = if (timeField.isEnabled) 1f else 0.6f
        dateField.contentDescription = context.getString(R.string.date_field_description, dateField.text)
        timeField.contentDescription = context.getString(R.string.time_field_description, timeField.text)
        val canClear = editable && (time != null || (!dateRequired && date != null))
        clearButton.visibility = if (editable) View.VISIBLE else View.GONE
        clearButton.isEnabled = canClear
        clearButton.contentDescription = context.getString(
            if (time != null) R.string.clear_time else R.string.clear_date,
        )
    }
}
