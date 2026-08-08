package dev.gf2log.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.MembershipPeriod
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.isImmutableMembershipBoundary
import dev.gf2log.app.management.isValidMembershipRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MemberDetailActivity : LocalizedActivity() {
    private lateinit var repository: PlatoonRepository
    private var uid: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uid = intent.getLongExtra(EXTRA_UID, -1)
        repository = PlatoonRepository(this)
        render()
    }

    private fun render() {
        val status = repository.listMemberStatuses().firstOrNull { it.uid == uid } ?: run {
            finish()
            return
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.member_nickname)
            setText(status.name)
            setSingleLine(true)
        }
        val noteInput = EditText(this).apply {
            hint = getString(R.string.member_private_note)
            setText(status.note)
            minLines = 2
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(context).apply {
                    text = getString(R.string.member_details)
                    textSize = 28f
                    setTypeface(typeface, Typeface.BOLD)
                }, matchWidth())
                addView(TextView(context).apply {
                    text = getString(
                        R.string.member_status_line,
                        status.uid,
                        getString(
                            if (status.isActive) {
                                R.string.active_member
                            } else {
                                R.string.departed_member
                            },
                        ),
                    )
                    textSize = 16f
                    setPadding(0, dp(4), 0, dp(8))
                }, matchWidth())
                addView(nameInput, matchWidth())
                addView(noteInput, matchWidth())
                addView(Button(context).apply {
                    text = getString(R.string.save_member)
                    setOnClickListener {
                        val saved = runCatching {
                            repository.updateMember(
                                status.uid,
                                nameInput.text.toString(),
                                noteInput.text.toString(),
                            )
                        }.getOrDefault(false)
                        Toast.makeText(
                            this@MemberDetailActivity,
                            getString(if (saved) R.string.saved else R.string.save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }, matchWidth())
                addView(Button(context).apply {
                    text = getString(R.string.add_membership_history)
                    setOnClickListener { addMembershipHistory(status) }
                }, matchWidth())
                addView(Button(context).apply {
                    text = getString(R.string.delete_member)
                    setTextColor(getColor(R.color.destructive_action))
                    setOnClickListener { confirmMemberDeletion(status) }
                }, matchWidth())
                addView(TextView(context).apply {
                    text = getString(R.string.membership_history)
                    textSize = 21f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(16), 0, dp(4))
                }, matchWidth())
                status.membershipPeriods.forEachIndexed { index, membershipPeriod ->
                    addView(membershipPeriodButton(status, membershipPeriod, index), matchWidth())
                }
            }, matchWidth())
        })
    }

    private fun confirmMemberDeletion(status: MemberStatus) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.delete_member)
            .setMessage(R.string.delete_member_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(getColor(R.color.destructive_action))
                setOnClickListener {
                    val deleted = runCatching {
                        repository.deleteMember(status.uid)
                    }.getOrDefault(false)
                    if (deleted) {
                        dialog.dismiss()
                        Toast.makeText(
                            this@MemberDetailActivity,
                            R.string.member_deleted,
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    } else {
                        Toast.makeText(
                            this@MemberDetailActivity,
                            R.string.member_delete_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun membershipPeriodButton(
        status: MemberStatus,
        membershipPeriod: MembershipPeriod,
        index: Int,
    ) = Button(this).apply {
        isAllCaps = false
        text = getString(
            R.string.membership_period_summary,
            index + 1,
            format(
                membershipPeriod.joinedDate,
                membershipPeriod.joinedAt,
                membershipPeriod.joinedTimeKnown,
            ),
            format(
                membershipPeriod.leftDate,
                membershipPeriod.leftAt,
                membershipPeriod.leftTimeKnown ?: (membershipPeriod.leftAt != null),
            ),
        )
        setOnClickListener { editMembershipPeriod(status, membershipPeriod) }
    }

    private fun editMembershipPeriod(status: MemberStatus, membershipPeriod: MembershipPeriod) {
        val joined = DateTimePickerInput(
            context = this,
            label = getString(R.string.join_field),
            initialValue = membershipPeriod.joinedAt,
            initialDate = membershipPeriod.joinedDate,
            initialTimeKnown = membershipPeriod.joinedTimeKnown,
            dateRequired = true,
            editable = !membershipPeriod.joinedSource.isImmutableMembershipBoundary(),
        )
        val left = DateTimePickerInput(
            context = this,
            label = getString(R.string.withdraw_field),
            initialValue = membershipPeriod.leftAt,
            initialDate = membershipPeriod.leftDate,
            initialTimeKnown = membershipPeriod.leftTimeKnown ?: (membershipPeriod.leftAt != null),
            editable = membershipPeriod.leftSource?.isImmutableMembershipBoundary() != true,
        )
        val note = EditText(this).apply {
            hint = getString(R.string.membership_note_hint)
            setText(membershipPeriod.note)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(joined, matchWidth())
            addView(left, matchWidth())
            addView(note, matchWidth())
            if (status.membershipPeriods.size == 1) {
                addView(TextView(context).apply {
                    setText(R.string.membership_period_delete_last_hint)
                    setTextColor(getColor(android.R.color.darker_gray))
                    setPadding(0, dp(8), 0, 0)
                }, matchWidth())
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_membership)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.delete, null)
            .setPositiveButton(R.string.save_member, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                setTextColor(getColor(R.color.destructive_action))
                isEnabled = status.membershipPeriods.size > 1
                setOnClickListener {
                    if (isEnabled) confirmMembershipPeriodDeletion(dialog, membershipPeriod)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val joinedBoundary = joined.boundary
                val leftBoundary = left.boundary
                val saved = joinedBoundary != null &&
                    isValidMembershipRange(joinedBoundary, leftBoundary) &&
                    runCatching {
                    repository.updateMembershipPeriod(
                        membershipPeriod.id,
                        joinedBoundary,
                        leftBoundary,
                        note.text.toString(),
                    )
                }.getOrDefault(false)
                Toast.makeText(
                    this,
                    getString(if (saved) R.string.saved else R.string.invalid_date),
                    Toast.LENGTH_SHORT,
                ).show()
                if (saved) {
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun confirmMembershipPeriodDeletion(
        editor: AlertDialog,
        membershipPeriod: MembershipPeriod,
    ) {
        val confirmation = AlertDialog.Builder(this)
            .setTitle(R.string.delete_membership_period)
            .setMessage(R.string.delete_membership_period_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()
        confirmation.setOnShowListener {
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(getColor(R.color.destructive_action))
                setOnClickListener {
                    val deleted = runCatching {
                        repository.deleteMembershipPeriod(membershipPeriod.id)
                    }.getOrDefault(false)
                    if (deleted) {
                        confirmation.dismiss()
                        editor.dismiss()
                        Toast.makeText(
                            this@MemberDetailActivity,
                            R.string.membership_period_deleted,
                            Toast.LENGTH_SHORT,
                        ).show()
                        render()
                    } else {
                        Toast.makeText(
                            this@MemberDetailActivity,
                            R.string.membership_period_delete_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        confirmation.show()
    }

    private fun addMembershipHistory(status: MemberStatus) {
        val joined = DateTimePickerInput(
            this,
            getString(R.string.join_field),
            dateRequired = true,
        )
        val withdrew = DateTimePickerInput(this, getString(R.string.withdraw_field))
        val note = EditText(this).apply {
            hint = getString(R.string.membership_note_hint)
            minLines = 2
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(joined, matchWidth())
            addView(withdrew, matchWidth())
            addView(note, matchWidth())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_membership_history)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val joinedBoundary = joined.boundary
                val withdrewBoundary = withdrew.boundary
                val saved = joinedBoundary != null &&
                    isValidMembershipRange(joinedBoundary, withdrewBoundary) &&
                    runCatching {
                    repository.addMembershipPeriod(
                        status.uid,
                        joinedBoundary,
                        withdrewBoundary,
                        note.text.toString(),
                    )
                }.getOrDefault(false)
                if (saved) {
                    dialog.dismiss()
                    render()
                } else {
                    Toast.makeText(this, R.string.invalid_date, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun format(
        date: java.time.LocalDate?,
        instant: Instant?,
        timeKnown: Boolean,
    ): String {
        val displayDate = date ?: instant?.atZone(ZoneId.systemDefault())?.toLocalDate()
            ?: return getString(R.string.unknown)
        return if (timeKnown && instant != null) {
            instant.atZone(ZoneId.systemDefault()).format(DISPLAY_TIME)
        } else {
            displayDate.format(DISPLAY_DATE)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_UID = "uid"
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm")
        private val DISPLAY_DATE = DateTimeFormatter.ofPattern("yy/MM/dd")
    }
}
