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
import dev.gf2log.app.management.MembershipTenure
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.EvidenceSource
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
                addView(TextView(context).apply {
                    text = getString(R.string.membership_history)
                    textSize = 21f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(16), 0, dp(4))
                }, matchWidth())
                status.tenures.forEachIndexed { index, tenure ->
                    addView(tenureButton(status, tenure, index), matchWidth())
                }
            }, matchWidth())
        })
    }

    private fun tenureButton(
        status: MemberStatus,
        tenure: MembershipTenure,
        index: Int,
    ) = Button(this).apply {
        isAllCaps = false
        text = getString(
            R.string.tenure_summary,
            index + 1,
            format(tenure.joinedAt),
            format(tenure.leftAt),
            tenure.joinedPrecision.name,
            tenure.leftPrecision?.name ?: "-",
        )
        val immutable = tenure.joinedSource == EvidenceSource.GAME_UPDATES ||
            tenure.leftSource == EvidenceSource.GAME_UPDATES
        isEnabled = !immutable
        alpha = if (immutable) 0.72f else 1f
        setOnClickListener { editTenure(tenure) }
    }

    private fun editTenure(tenure: MembershipTenure) {
        val joined = DateTimePickerInput(this, getString(R.string.join_field), tenure.joinedAt)
        val left = DateTimePickerInput(this, getString(R.string.withdraw_field), tenure.leftAt)
        val note = EditText(this).apply {
            hint = getString(R.string.membership_note_hint)
            setText(tenure.note)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(joined, matchWidth())
            addView(left, matchWidth())
            addView(note, matchWidth())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_membership)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_member) { _, _ ->
                val saved = runCatching {
                    repository.updateTenure(
                        tenure.id,
                        joined.instant,
                        left.instant,
                        note.text.toString(),
                    )
                }.getOrDefault(false)
                Toast.makeText(
                    this,
                    getString(if (saved) R.string.saved else R.string.invalid_date),
                    Toast.LENGTH_SHORT,
                ).show()
                if (saved) render()
            }
            .show()
    }

    private fun addMembershipHistory(status: MemberStatus) {
        val joined = DateTimePickerInput(this, getString(R.string.join_field))
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
                val joinedAt = joined.instant
                val withdrewAt = withdrew.instant
                val validRange = joinedAt == null ||
                    withdrewAt == null ||
                    !withdrewAt.isBefore(joinedAt)
                val saved = validRange && runCatching {
                    repository.addTenure(
                        status.uid,
                        joinedAt,
                        withdrewAt,
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

    private fun format(instant: Instant?): String =
        instant?.atZone(ZoneId.systemDefault())?.format(DISPLAY_TIME) ?: getString(R.string.unknown)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_UID = "uid"
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm")
    }
}
