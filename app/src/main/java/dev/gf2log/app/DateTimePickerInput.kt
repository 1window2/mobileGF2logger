package dev.gf2log.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.management.MembershipBoundaryValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Calendar input with an optional clock value.
 *
 * Dates are stored explicitly so a date-only manual boundary remains stable if
 * the device timezone later changes. A date-only value uses local midnight as
 * its representative Instant, while [MembershipBoundaryValue.timeKnown]
 * preserves that the time itself is unknown.
 */
internal class DateTimePickerInput(
    context: Context,
    label: String,
    initialValue: Instant? = null,
    initialDate: LocalDate? = null,
    initialTimeKnown: Boolean = initialValue != null,
    private val dateRequired: Boolean = false,
    editable: Boolean = true,
) : LinearLayout(context) {
    private val zone: ZoneId = ZoneId.systemDefault()
    private var selectedDate: LocalDate? =
        initialDate ?: initialValue?.atZone(zone)?.toLocalDate()
    private var selectedTime: LocalTime? =
        initialValue
            ?.takeIf { initialTimeKnown }
            ?.atZone(zone)
            ?.toLocalTime()
            ?.withSecond(0)
            ?.withNano(0)

    val date: LocalDate?
        get() = selectedDate

    val boundary: MembershipBoundaryValue?
        get() = selectedDate?.let { date ->
            MembershipBoundaryValue(
                date = date,
                instant = date.atTime(selectedTime ?: LocalTime.MIDNIGHT)
                    .atZone(zone)
                    .toInstant(),
                timeKnown = selectedTime != null,
            )
        }

    private val dateInput = pickerField(context.getString(R.string.date_unset)) {
        showDatePicker()
    }
    private val timeInput = pickerField(context.getString(R.string.time_optional)) {
        if (selectedDate == null) {
            Toast.makeText(context, R.string.select_date_first, Toast.LENGTH_SHORT).show()
        } else {
            showTimePicker()
        }
    }
    private val clearDateButton = Button(context).apply {
        text = context.getString(R.string.clear_date)
        setOnClickListener {
            selectedDate = null
            selectedTime = null
            renderValue()
        }
    }
    private val clearTimeButton = Button(context).apply {
        text = context.getString(R.string.clear_time)
        setOnClickListener {
            selectedTime = null
            renderValue()
        }
    }

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = label
            setPadding(0, dp(6), 0, dp(2))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(dateInput, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!dateRequired) {
                addView(
                    clearDateButton,
                    LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(timeInput, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                clearTimeButton,
                LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setBoundaryEditable(editable)
        renderValue()
    }

    private fun pickerField(hintText: String, onClick: () -> Unit) =
        EditText(context).apply {
            isFocusable = false
            isClickable = true
            hint = hintText
            setSingleLine(true)
            setOnClickListener { onClick() }
        }

    private fun showDatePicker() {
        val seed = selectedDate ?: LocalDate.now(zone)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                renderValue()
            },
            seed.year,
            seed.monthValue - 1,
            seed.dayOfMonth,
        ).show()
    }

    private fun showTimePicker() {
        val seed = selectedTime ?: LocalTime.now(zone).withSecond(0).withNano(0)
        TimePickerDialog(
            context,
            { _, hour, minute ->
                selectedTime = LocalTime.of(hour, minute)
                renderValue()
            },
            seed.hour,
            seed.minute,
            true,
        ).show()
    }

    private fun setBoundaryEditable(editable: Boolean) {
        dateInput.isEnabled = editable
        timeInput.isEnabled = editable
        clearDateButton.visibility = if (editable && !dateRequired) View.VISIBLE else View.GONE
        clearTimeButton.visibility = if (editable) View.VISIBLE else View.GONE
        alpha = if (editable) 1f else 0.72f
    }

    private fun renderValue() {
        dateInput.setText(selectedDate?.format(DATE).orEmpty())
        timeInput.setText(selectedTime?.format(TIME).orEmpty())
        timeInput.isEnabled = dateInput.isEnabled && selectedDate != null
        clearTimeButton.isEnabled = selectedDate != null && selectedTime != null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
