package dev.gf2log.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.settings.WeeklyCutlines
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.LabeledField
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.switchRow
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField

/** Per-Platoon cutlines that highlight weekly cells which are, or may remain, below a target. */
class WeeklySettingsActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var preferences: WeeklyCutlinePreferences
    private lateinit var initial: WeeklyCutlines
    private val inputs = mutableListOf<Threshold>()
    private var restoredEnabled: BooleanArray? = null
    private val unsavedBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = leave()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        val scope = profileBinding.scope ?: run {
            finish()
            return
        }
        preferences = WeeklyCutlinePreferences(this, scope.storageId)
        initial = preferences.read()
        restoredEnabled = savedInstanceState?.getBooleanArray(STATE_ENABLED)
        onBackPressedDispatcher.addCallback(this, unsavedBack)
        val screen = Screen(this)
        screen.topBar
            .setNavigation(::leave)
            .setTitle(getString(R.string.weekly_table_settings))
            .setActions(listOf(BarAction(R.drawable.ic_check, getString(R.string.save_settings)) { save() }))
        val content = column {
            addView(text(getString(R.string.cutline_description), TextRole.SECONDARY), lp(top = 4))
            addView(sectionHeader(getString(R.string.daily_cutlines)), lp())
            addView(panel(padding = 8) {
                threshold(R.id.cutline_daily_merit, R.string.merit_cutline, initial.dailyMerit, null)
                threshold(R.id.cutline_daily_score, R.string.gunsmoke_score_cutline, initial.dailyGunsmokeScore, null)
                threshold(R.id.cutline_daily_attempts, R.string.gunsmoke_attempt_cutline, initial.dailyGunsmokeAttempts?.toLong(), DAILY_ATTEMPTS)
            }, lp())
            addView(sectionHeader(getString(R.string.weekly_cutlines)), lp())
            addView(panel(padding = 8) {
                threshold(R.id.cutline_weekly_merit, R.string.merit_cutline, initial.weeklyMerit, null)
                threshold(R.id.cutline_weekly_score, R.string.gunsmoke_score_cutline, initial.weeklyGunsmokeScore, null)
                threshold(R.id.cutline_weekly_attempts, R.string.gunsmoke_attempt_cutline, initial.weeklyGunsmokeAttempts?.toLong(), WEEKLY_ATTEMPTS)
                threshold(R.id.cutline_login_days, R.string.weekly_login_cutline, initial.weeklyLoginDays?.toLong(), WEEK_DAYS)
                threshold(R.id.cutline_patrol_days, R.string.weekly_patrol_cutline, initial.weeklyPatrolDays?.toLong(), WEEK_DAYS)
            }, lp())
            addView(button(getString(R.string.save_settings), Controls.Role.PRIMARY) { save() }, lp(top = 16))
        }
        screen.setBody(Screen.scrollingBody(this, content))
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) finish()
    }

    private fun LinearLayout.threshold(id: Int, label: Int, value: Long?, maximum: Int?) {
        val input = textField(getString(R.string.cutline_value), id = id, inputType = InputType.TYPE_CLASS_NUMBER).apply {
            setText(value?.toString().orEmpty())
        }
        val supporting = maximum?.let { getString(R.string.cutline_range, it) }
        val field = LabeledField(context, getString(R.string.cutline_value), input, supporting)
        val threshold = Threshold(input, field, maximum, restoredEnabled?.getOrNull(inputs.size) ?: (value != null))
        field.visibility = if (threshold.enabled) View.VISIBLE else View.GONE
        addView(switchRow(getString(label), supporting, threshold.enabled) { checked ->
            threshold.enabled = checked
            field.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) input.requestFocus()
        }, lp())
        addView(field, lp(start = 4, end = 4, bottom = 8))
        inputs += threshold
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBooleanArray(STATE_ENABLED, inputs.map(Threshold::enabled).toBooleanArray())
        super.onSaveInstanceState(outState)
    }

    private fun readValues(): List<Long?>? {
        var valid = true
        val values = inputs.map { threshold ->
            if (!threshold.enabled) {
                threshold.field.showError(null)
                return@map null
            }
            val parsed = threshold.input.text.toString().trim().toLongOrNull()
            val ok = parsed != null && parsed >= 0 && threshold.maximum?.let { parsed <= it } != false
            threshold.field.showError(
                if (ok) null else threshold.maximum?.let { getString(R.string.cutline_range_error, it) }
                    ?: getString(R.string.cutline_number_error),
            )
            if (!ok) valid = false
            parsed
        }
        return values.takeIf { valid }
    }

    private fun current(values: List<Long?>) = WeeklyCutlines(
        dailyMerit = values[0],
        dailyGunsmokeScore = values[1],
        dailyGunsmokeAttempts = values[2]?.toInt(),
        weeklyMerit = values[3],
        weeklyGunsmokeScore = values[4],
        weeklyGunsmokeAttempts = values[5]?.toInt(),
        weeklyLoginDays = values[6]?.toInt(),
        weeklyPatrolDays = values[7]?.toInt(),
    )

    private fun save() {
        val values = readValues() ?: return
        preferences.write(current(values))
        Feedback.show(this, R.string.saved)
        finish()
    }

    private fun leave() {
        val values = readValues()
        if (values != null && current(values) == initial) {
            finish()
            return
        }
        Dialogs.show(
            Dialogs.builder(this)
                .setTitle(R.string.unsaved_changes_title)
                .setMessage(R.string.unsaved_changes_message)
                .setNegativeButton(R.string.discard) { _, _ -> finish() }
                .setPositiveButton(R.string.save_settings) { _, _ -> save() },
        )
    }

    private class Threshold(
        val input: EditText,
        val field: LabeledField,
        val maximum: Int?,
        var enabled: Boolean,
    )

    private companion object {
        const val DAILY_ATTEMPTS = 3
        const val WEEKLY_ATTEMPTS = 21
        const val WEEK_DAYS = 7
        const val STATE_ENABLED = "cutlines.enabled"
    }
}
