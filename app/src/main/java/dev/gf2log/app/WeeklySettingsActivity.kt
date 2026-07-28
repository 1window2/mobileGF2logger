package dev.gf2log.app

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.settings.WeeklyCutlines

class WeeklySettingsActivity : LocalizedActivity() {
    private lateinit var dailyMerit: ThresholdInput
    private lateinit var dailyScore: ThresholdInput
    private lateinit var dailyAttempts: ThresholdInput
    private lateinit var weeklyMerit: ThresholdInput
    private lateinit var weeklyScore: ThresholdInput
    private lateinit var weeklyAttempts: ThresholdInput
    private lateinit var loginDays: ThresholdInput
    private lateinit var patrolDays: ThresholdInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.weekly_table_settings)
        setContentView(buildContentView(WeeklyCutlinePreferences(this).read()))
    }

    private fun buildContentView(current: WeeklyCutlines): ScrollView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))

            addView(TextView(context).apply {
                text = getString(R.string.weekly_table_settings)
                textSize = 26f
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.cutline_description)
                setPadding(0, dp(6), 0, dp(12))
            }, matchWidth())

            addSectionHeading(R.string.daily_cutlines)
            dailyMerit = thresholdInput(R.string.merit_cutline, current.dailyMerit, null)
            dailyScore = thresholdInput(
                R.string.gunsmoke_score_cutline,
                current.dailyGunsmokeScore,
                null,
            )
            dailyAttempts = thresholdInput(
                R.string.gunsmoke_attempt_cutline,
                current.dailyGunsmokeAttempts?.toLong(),
                3,
            )

            addSectionHeading(R.string.weekly_cutlines)
            weeklyMerit = thresholdInput(R.string.merit_cutline, current.weeklyMerit, null)
            weeklyScore = thresholdInput(
                R.string.gunsmoke_score_cutline,
                current.weeklyGunsmokeScore,
                null,
            )
            weeklyAttempts = thresholdInput(
                R.string.gunsmoke_attempt_cutline,
                current.weeklyGunsmokeAttempts?.toLong(),
                21,
            )
            loginDays = thresholdInput(
                R.string.weekly_login_cutline,
                current.weeklyLoginDays?.toLong(),
                7,
            )
            patrolDays = thresholdInput(
                R.string.weekly_patrol_cutline,
                current.weeklyPatrolDays?.toLong(),
                7,
            )

            addView(Button(context).apply {
                text = getString(R.string.save_settings)
                setOnClickListener { save() }
            }, matchWidth())
        }
        return ScrollView(this).apply { addView(container, matchWidth()) }
    }

    private fun LinearLayout.addSectionHeading(label: Int) {
        addView(TextView(context).apply {
            text = getString(label)
            textSize = 20f
            setPadding(0, dp(12), 0, dp(4))
        }, matchWidth())
    }

    private fun LinearLayout.thresholdInput(
        label: Int,
        value: Long?,
        maximum: Int?,
    ): ThresholdInput {
        val input = ThresholdInput(
            checkBox = CheckBox(context).apply {
                text = getString(label)
                isChecked = value != null
            },
            value = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                hint = getString(R.string.cutline_value)
                setText(value?.toString().orEmpty())
                isEnabled = value != null
            },
            maximum = maximum,
        )
        input.checkBox.setOnCheckedChangeListener { _, checked ->
            input.value.isEnabled = checked
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            addView(input.checkBox, LinearLayout.LayoutParams(0, wrap(), 2f))
            addView(input.value, LinearLayout.LayoutParams(0, wrap(), 1f))
        }, matchWidth())
        return input
    }

    private fun save() {
        val values = listOf(
            dailyMerit,
            dailyScore,
            dailyAttempts,
            weeklyMerit,
            weeklyScore,
            weeklyAttempts,
            loginDays,
            patrolDays,
        ).map { runCatching { it.read() }.getOrNull() }
        if (values.any { it == INVALID }) {
            Toast.makeText(this, R.string.invalid_cutline, Toast.LENGTH_SHORT).show()
            return
        }
        WeeklyCutlinePreferences(this).write(
            WeeklyCutlines(
                dailyMerit = values[0],
                dailyGunsmokeScore = values[1],
                dailyGunsmokeAttempts = values[2]?.toInt(),
                weeklyMerit = values[3],
                weeklyGunsmokeScore = values[4],
                weeklyGunsmokeAttempts = values[5]?.toInt(),
                weeklyLoginDays = values[6]?.toInt(),
                weeklyPatrolDays = values[7]?.toInt(),
            ),
        )
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun ThresholdInput.read(): Long? {
        if (!checkBox.isChecked) return null
        val parsed = value.text.toString().toLongOrNull() ?: return INVALID
        if (parsed < 0 || maximum?.let { parsed > it } == true) return INVALID
        return parsed
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private data class ThresholdInput(
        val checkBox: CheckBox,
        val value: EditText,
        val maximum: Int?,
    )

    private companion object {
        const val INVALID = Long.MIN_VALUE
    }
}
