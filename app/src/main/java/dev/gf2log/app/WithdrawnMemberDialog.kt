package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.isValidMembershipRange
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.LabeledField
import dev.gf2log.app.ui.errorLine
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.showMessage
import dev.gf2log.app.ui.textField

/** Records a member who left before capture began, with a required join and withdrawal date. */
internal object WithdrawnMemberDialog {
    fun show(
        activity: Activity,
        work: BackgroundWork,
        repository: PlatoonRepository,
        isKnownUid: (Long) -> Boolean,
        onAdded: () -> Unit,
    ) = with(activity) {
        val zone = repository.gameZone()
        val uid = LabeledField(this, getString(R.string.uid), textField(getString(R.string.uid_hint), inputType = InputType.TYPE_CLASS_NUMBER))
        val nickname = LabeledField(this, getString(R.string.member_nickname), textField(getString(R.string.member_nickname)))
        val joined = DateTimePickerInput(this, getString(R.string.join_label), dateRequired = true, zone = zone)
        val withdrew = DateTimePickerInput(this, getString(R.string.withdraw_label), dateRequired = true, zone = zone)
        val note = LabeledField(
            this,
            getString(R.string.membership_note_hint),
            textField(getString(R.string.membership_note_hint), singleLine = false),
        )
        val error = errorLine()
        val content = Dialogs.content(this) {
            listOf<View>(uid, nickname, joined, withdrew, note, error).forEachIndexed { index, view ->
                addView(view, lp(top = if (index == 0) 0 else 12))
            }
        }
        Dialogs.show(
            Dialogs.builder(this)
                .setTitle(R.string.add_withdrawn_member)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, null),
        ) { dialog ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                val uidValue = uid.input.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }
                val name = nickname.input.text.toString().trim()
                val joinedBoundary = joined.boundary
                val withdrewBoundary = withdrew.boundary
                val problem = when {
                    uidValue == null -> R.string.invalid_uid
                    isKnownUid(uidValue) -> R.string.duplicate_uid
                    name.isEmpty() -> R.string.invalid_nickname
                    joinedBoundary == null || withdrewBoundary == null -> R.string.invalid_dates_required
                    !isValidMembershipRange(joinedBoundary, withdrewBoundary) -> R.string.invalid_date_order
                    else -> null
                }
                if (problem != null || uidValue == null || joinedBoundary == null || withdrewBoundary == null) {
                    error.showMessage(getString(problem ?: R.string.invalid_withdrawn_member))
                    return@setOnClickListener
                }
                button.isEnabled = false
                val noteText = note.input.text.toString()
                work.run(
                    task = { repository.addWithdrawnMember(uidValue, name, joinedBoundary, withdrewBoundary, noteText) },
                ) { result ->
                    button.isEnabled = true
                    if (result.getOrDefault(false)) {
                        dialog.dismiss()
                        Feedback.show(this, R.string.saved)
                        onAdded()
                    } else {
                        error.showMessage(getString(R.string.invalid_withdrawn_member))
                    }
                }
            }
        }
    }
}
