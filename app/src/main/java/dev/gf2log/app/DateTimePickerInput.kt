package dev.gf2log.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only text input that opens Android's calendar and clock pickers.
 *
 * The selected value is stored as an [Instant] in the device time zone. The
 * clear action deliberately allows unknown historical boundaries.
 */
internal class DateTimePickerInput(
    context: Context,
    label: String,
    initialValue: Instant? = null,
) : LinearLayout(context) {
    var instant: Instant? = initialValue
        private set

    private val valueInput = EditText(context).apply {
        isFocusable = false
        isClickable = true
        hint = context.getString(R.string.date_time_unset)
        setSingleLine(true)
        setOnClickListener { showDatePicker() }
    }

    init {
        orientation = HORIZONTAL
        addView(TextView(context).apply {
            text = label
            setPadding(0, 0, dp(8), 0)
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(valueInput, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(context).apply {
            text = context.getString(R.string.clear)
            setOnClickListener {
                instant = null
                renderValue()
            }
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderValue()
    }

    private fun showDatePicker() {
        val zone = ZoneId.systemDefault()
        val seed = instant?.atZone(zone)?.toLocalDateTime() ?: LocalDateTime.now(zone)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        instant = LocalDateTime.of(year, month + 1, day, hour, minute)
                            .atZone(zone)
                            .toInstant()
                        renderValue()
                    },
                    seed.hour,
                    seed.minute,
                    true,
                ).show()
            },
            seed.year,
            seed.monthValue - 1,
            seed.dayOfMonth,
        ).show()
    }

    private fun renderValue() {
        valueInput.setText(
            instant
                ?.atZone(ZoneId.systemDefault())
                ?.format(DISPLAY_TIME)
                .orEmpty(),
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
