package dev.gf2log.app

import dev.gf2log.app.management.ManualTextPolicy
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import dev.gf2log.app.management.EvidenceSource
import dev.gf2log.app.management.MemberProfileDraft
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.MembershipPeriod
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.management.SnapshotMember
import dev.gf2log.app.management.isJoinedBoundaryEditable
import dev.gf2log.app.management.isMembershipBoundaryWithinPresent
import dev.gf2log.app.management.isLeftBoundaryEditable
import dev.gf2log.app.management.isValidMembershipRange
import dev.gf2log.app.management.member
import dev.gf2log.app.ui.onTextChanged
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.DialogDraft
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.LabeledField
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.MenuEntry
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.errorLine
import dev.gf2log.app.ui.showMessage
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One member's profile, private note, and editable membership history. */
class MemberDetailActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var scope: PlatoonStorageScope
    private lateinit var repository: PlatoonRepository
    private lateinit var screen: Screen
    private val work = BackgroundWork(this, "GF2MemberDetail")
    private var uid = -1L
    private var model: Model? = null
    private var inputsInitialized = false
    private var finishAfterSave = false

    /** False while the last load failed: edits wait, but a typed draft stays in its fields. */
    private var available = true

    private lateinit var content: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var periods: LinearLayout
    private lateinit var nickname: EditText
    private lateinit var note: EditText
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uid = intent.getLongExtra(EXTRA_UID, -1)
        profileBinding = ActivePlatoonScopeBinding(this, pinnedStorage(savedInstanceState))
        val bound = profileBinding.scope
        // A draft typed for a Platoon that is gone never shows against another Platoon's member.
        if (uid <= 0 || bound == null || profileBinding.lostPinnedStorage) {
            finish()
            return
        }
        scope = bound
        repository = PlatoonRepository(this, scope)
        inputsInitialized = savedInstanceState?.getBoolean(STATE_INPUTS_INITIALIZED) == true
        screen = Screen(this)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        screen.topBar
            .setNavigation(::leave)
            .setTitle(getString(R.string.member_details))
            .setActions(
                emptyList(),
                overflow = listOf(MenuEntry(getString(R.string.delete_member)) { confirmMemberDeletion() }),
            )
        buildContent()
        screen.setBody(Screen.scrollingBody(this, content))
    }

    override fun onScreenResumed() {
        if (!profileBinding.isSameStorage(this)) {
            onScopeChanged()
            return
        }
        load()
    }

    /**
     * The member belongs to the previous Platoon scope; unsaved edits are not dropped silently.
     * A recreated screen first loads the member again, so its restored draft can be compared.
     */
    private fun onScopeChanged() {
        if (work.isBusy(SAVE)) {
            // The edits are already being saved; leave once that save lands.
            finishAfterSave = true
            return
        }
        if (model == null && inputsInitialized) {
            if (!work.isBusy(LOAD)) load()
            return
        }
        if (isDirty()) {
            confirmDraftBeforeScopeChange(R.string.save_member, { saveProfile(thenFinish = true) }, ::finish)
        } else {
            finish()
        }
    }

    override fun boundStorageId(): String? =
        if (::profileBinding.isInitialized) profileBinding.scope?.storageId else null

    /**
     * Always kept: on a Platoon change this screen closes rather than follow it, and its fields
     * restore their text by id, which must never land on another Platoon's member.
     */
    override fun pinsBoundStorage(): Boolean = true

    override fun hasUnsavedDraft(): Boolean = ::saveButton.isInitialized && (isDirty() || work.isBusy(SAVE))

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INPUTS_INITIALIZED, inputsInitialized)
        super.onSaveInstanceState(outState)
    }

    private fun buildContent() {
        nickname = textField(getString(R.string.member_nickname), id = R.id.member_nickname_input, maxLength = ManualTextPolicy.MAX_MEMBER_NAME_CHARS)
        note = textField(getString(R.string.member_private_note_hint), id = R.id.member_note_input, singleLine = false, maxLength = ManualTextPolicy.MAX_NOTE_CHARS)
        saveButton = button(getString(R.string.save_member), Controls.Role.PRIMARY) { saveProfile() }
        nickname.onTextChanged { updateSaveState() }
        note.onTextChanged { updateSaveState() }
        statusPanel = panel()
        periods = column()
        content = column {
            addView(statusPanel, lp(top = 4))
            addView(sectionHeader(getString(R.string.member_profile)), lp())
            addView(panel {
                addView(LabeledField(context, getString(R.string.member_nickname), nickname, getString(R.string.member_nickname_detail)), lp())
                addView(LabeledField(context, getString(R.string.member_private_note), note, getString(R.string.member_private_note_detail)), lp(top = 12))
                addView(saveButton, lp(top = 12))
            }, lp())
            addView(row {
                addView(sectionHeader(getString(R.string.membership_history)), lp(width = 0, weight = 1f))
                addView(button(getString(R.string.add_membership_history_short), Controls.Role.TERTIARY, R.drawable.ic_add) {
                    if (available) addMembershipPeriod()
                }, lp(WRAP, WRAP, top = 8))
            }, lp())
            addView(periods, lp())
        }
        statusPanel.addView(StateViews.loading(this, getString(R.string.loading_members)))
        updateSaveState()
    }

    private fun load() {
        work.run(
            key = LOAD,
            task = {
                val status = repository.listMemberStatuses().firstOrNull { it.uid == uid }
                status?.let {
                    Model(
                        status = it,
                        latest = repository.latestSnapshot()?.member(uid),
                        zone = repository.gameZone(),
                    )
                }
            },
        ) { result ->
            val loaded = result.getOrNull()
            if (loaded == null) {
                if (result.isSuccess) finish() else render(null, result.exceptionOrNull())
                return@run
            }
            available = true
            model = loaded
            // A blank nickname follows the in-game name, which the hint shows.
            nickname.hint = loaded.status.currentName
            if (!inputsInitialized) {
                nickname.setText(MemberProfileDraft.nicknameInput(loaded.status))
                note.setText(loaded.status.note)
                inputsInitialized = true
            }
            render(loaded)
            reopenMembershipDialog(loaded.status)
            if (!profileBinding.isSameStorage(this)) onScopeChanged()
        }
    }

    private fun render(loaded: Model?, error: Throwable? = null) {
        statusPanel.removeAllViews()
        periods.removeAllViews()
        if (loaded == null) {
            available = false
            updateSaveState()
            statusPanel.addView(
                PlatoonDataStates.failure(this, work, scope, error, getString(R.string.platoon_load_failed)) { load() },
            )
            return
        }
        val status = loaded.status
        screen.topBar.setTitle(status.name, Labels.uid(this, status.uid))
        statusPanel.addView(row {
            addView(text(getString(R.string.member_since, firstSeen(status, loaded.zone)), TextRole.SECONDARY), lp(width = 0, weight = 1f))
            addView(badge(
                getString(if (status.isActive) R.string.active_member else R.string.departed_member),
                if (status.isActive) R.color.success_surface else R.color.surface_variant,
                if (status.isActive) R.color.success_text else R.color.text_secondary,
            ), lp(WRAP, WRAP))
        }, lp())
        val latest = loaded.latest
        val stats = listOf(
            getString(R.string.level) to (latest?.level ?: status.level).toString(),
            getString(R.string.merit_this_week) to (latest?.weeklyMerit?.toString() ?: "—"),
            getString(R.string.total_merit) to (latest?.totalMerit?.toString() ?: "—"),
            getString(R.string.last_login) to (latest?.lastLogin?.takeIf { it > 0 }?.let {
                Labels.format(Labels.DATE_TIME, Instant.ofEpochSecond(it), loaded.zone)
            } ?: "—"),
        )
        stats.chunked(2).forEach { pair ->
            statusPanel.addView(row(Gravity.TOP) {
                pair.forEachIndexed { index, (label, value) ->
                    addView(column {
                        isFocusable = true
                        contentDescription = Labels.labeled(context, label, value)
                        addView(text(label, TextRole.CAPTION) { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp())
                        addView(text(value, TextRole.SUBTITLE) { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(top = 2))
                    }, lp(width = 0, weight = 1f, start = if (index == 0) 0 else 12))
                }
            }, lp(top = 12))
        }
        Labels.zoneCaption(this, loaded.zone)?.let { statusPanel.addView(text(it, TextRole.CAPTION), lp(top = 12)) }
        val rows = status.membershipPeriods.mapIndexed { index, period ->
            navigationRow(
                title = getString(R.string.membership_period_title, index + 1) +
                    if (period.leftAt == null && period.leftDate == null) " · " + getString(R.string.current_period) else "",
                detail = getString(
                    R.string.membership_period_detail,
                    boundaryText(period.joinedDate, period.joinedAt, period.joinedTimeKnown, loaded.zone),
                    if (period.leftAt == null && period.leftDate == null) {
                        getString(R.string.present)
                    } else {
                        boundaryText(period.leftDate, period.leftAt, period.leftTimeKnown ?: (period.leftAt != null), loaded.zone)
                    },
                    sourceLabel(period.joinedSource),
                    period.leftSource?.let(::sourceLabel) ?: "—",
                ) + period.note.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty(),
                icon = R.drawable.ic_calendar,
            ) { editMembershipPeriod(status, period) }
        }
        periods.addView(
            if (rows.isEmpty()) StateViews.empty(this, R.drawable.ic_calendar, getString(R.string.no_membership_periods)) else group(rows),
            lp(),
        )
        updateSaveState()
    }

    /** Unsaved nickname or note edits are confirmed instead of silently discarded. */
    private fun leave() {
        if (!::saveButton.isInitialized || !isDirty()) {
            finish()
            return
        }
        if (work.isBusy(SAVE)) {
            // The edits are already being saved; leave once that save lands.
            finishAfterSave = true
            return
        }
        Dialogs.show(
            Dialogs.builder(this)
                .setTitle(R.string.unsaved_changes_title)
                .setMessage(R.string.unsaved_changes_message)
                .setNegativeButton(R.string.discard) { _, _ -> finish() }
                // Leave only once the save lands; a failure keeps the screen and its edits.
                .setPositiveButton(R.string.save_member) { _, _ -> saveProfile(thenFinish = true) },
        )
    }

    /** Whether the inputs differ from the stored profile, compared in their stored form. */
    private fun isDirty(): Boolean {
        val loaded = model?.status ?: return false
        return MemberProfileDraft.isDirty(nickname.text.toString(), note.text.toString(), loaded)
    }

    private fun updateSaveState() {
        saveButton.isEnabled = available && !work.isBusy(SAVE) && isDirty()
    }

    private fun saveProfile(thenFinish: Boolean = false) {
        if (work.isBusy(SAVE)) return
        val name = nickname.text.toString()
        val noteText = note.text.toString()
        work.run(key = SAVE, task = { repository.updateMember(uid, name, noteText) }) { result ->
            val saved = result.getOrDefault(false)
            Feedback.show(this, if (saved) R.string.saved else platoonDataFailureMessage(result.exceptionOrNull(), R.string.save_failed))
            if (saved && (thenFinish || finishAfterSave)) {
                finish()
                return@run
            }
            finishAfterSave = false
            // Re-seed the fields in stored form unless the user kept typing during the save.
            if (saved && nickname.text.toString() == name && note.text.toString() == noteText) {
                inputsInitialized = false
            }
            load()
        }
        updateSaveState()
    }

    private fun confirmMemberDeletion() {
        Dialogs.confirm(this, R.string.delete_member, R.string.delete_member_warning, R.string.delete, destructive = true) confirm@{
            if (work.isBusy(DELETE)) return@confirm
            work.run(key = DELETE, task = { repository.deleteMember(uid) }) { result ->
                if (result.getOrDefault(false)) {
                    Feedback.show(this, R.string.member_deleted)
                    finish()
                } else {
                    Feedback.show(this, platoonDataFailureMessage(result.exceptionOrNull(), R.string.member_delete_failed))
                }
            }
        }
    }

    /** A membership editor that was open before recreation comes back with what was typed. */
    private fun reopenMembershipDialog(status: MemberStatus) {
        takeDialogDraft(ADD_PERIOD_DIALOG)?.let(::addMembershipPeriod)
        takeDialogDraft(EDIT_PERIOD_DIALOG)?.let { draft ->
            val period = status.membershipPeriods.firstOrNull { it.id == draft.arguments.getLong(ARG_PERIOD_ID) }
            period?.let { editMembershipPeriod(status, it, draft) }
        }
    }

    private fun editMembershipPeriod(status: MemberStatus, period: MembershipPeriod, restored: DialogDraft? = null) {
        val zone = model?.zone ?: return
        val joined = DateTimePickerInput(
            context = this,
            label = getString(R.string.join_label),
            initialValue = period.joinedAt,
            initialDate = period.joinedDate,
            initialTimeKnown = period.joinedTimeKnown,
            dateRequired = true,
            editable = period.isJoinedBoundaryEditable,
            zone = zone,
            stateId = R.id.dialog_joined_input,
        )
        val left = DateTimePickerInput(
            context = this,
            label = getString(R.string.withdraw_label),
            initialValue = period.leftAt,
            initialDate = period.leftDate,
            initialTimeKnown = period.leftTimeKnown ?: (period.leftAt != null),
            editable = period.isLeftBoundaryEditable,
            zone = zone,
            stateId = R.id.dialog_left_input,
        )
        val noteInput = textField(getString(R.string.membership_note_hint), id = R.id.dialog_note_input, singleLine = false, maxLength = ManualTextPolicy.MAX_NOTE_CHARS).apply { setText(period.note) }
        val error = errorLine()
        val canDelete = status.membershipPeriods.size > 1
        val body = Dialogs.content(this) {
            addView(joined, lp())
            addView(left, lp(top = 12))
            addView(LabeledField(context, getString(R.string.membership_note_hint), noteInput), lp(top = 12))
            addView(error, lp(top = 8))
            if (!canDelete) addView(text(getString(R.string.membership_period_delete_last_hint), TextRole.CAPTION), lp(top = 8))
        }
        val builder = Dialogs.builder(this)
            .setTitle(R.string.edit_membership)
            .setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_member, null)
        if (canDelete) builder.setNeutralButton(R.string.delete, null)
        val arguments = Bundle().apply { putLong(ARG_PERIOD_ID, period.id) }
        Dialogs.showRestorable(builder, EDIT_PERIOD_DIALOG, body, arguments, restored) { dialog ->
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(getColor(R.color.destructive_action))
                setOnClickListener { confirmMembershipPeriodDeletion(dialog, period) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                val joinedBoundary = joined.boundary
                val leftBoundary = left.boundary
                if (joinedBoundary == null || !isValidMembershipRange(joinedBoundary, leftBoundary)) {
                    error.showMessage(getString(R.string.invalid_date))
                    return@setOnClickListener
                }
                // Packet-linked sides are read-only and follow the server clock, so only editable
                // sides must not lie in the future.
                val futureJoin = period.isJoinedBoundaryEditable &&
                    !isMembershipBoundaryWithinPresent(joinedBoundary, zone)
                val futureLeft = period.isLeftBoundaryEditable && leftBoundary != null &&
                    !isMembershipBoundaryWithinPresent(leftBoundary, zone)
                if (futureJoin || futureLeft) {
                    error.showMessage(getString(R.string.invalid_future_date))
                    return@setOnClickListener
                }
                button.isEnabled = false
                val noteText = noteInput.text.toString()
                work.run(
                    task = { repository.updateMembershipPeriod(period.id, joinedBoundary, leftBoundary, noteText) },
                ) { result ->
                    button.isEnabled = true
                    if (result.getOrDefault(false)) {
                        dialog.dismiss()
                        Feedback.show(this, R.string.saved)
                        load()
                    } else {
                        error.showMessage(
                            getString(platoonDataFailureMessage(result.exceptionOrNull(), R.string.membership_period_save_failed)),
                        )
                    }
                }
            }
        }
    }

    private fun confirmMembershipPeriodDeletion(editor: AlertDialog, period: MembershipPeriod) {
        Dialogs.confirm(
            this,
            R.string.delete_membership_period,
            R.string.delete_membership_period_warning,
            R.string.delete,
            destructive = true,
        ) confirm@{
            if (work.isBusy(DELETE)) return@confirm
            work.run(key = DELETE, task = { repository.deleteMembershipPeriod(period.id) }) { result ->
                if (result.getOrDefault(false)) {
                    editor.dismiss()
                    Feedback.show(this, R.string.membership_period_deleted)
                    load()
                } else {
                    Feedback.show(
                        this,
                        platoonDataFailureMessage(result.exceptionOrNull(), R.string.membership_period_delete_failed),
                        long = true,
                    )
                }
            }
        }
    }

    private fun addMembershipPeriod(restored: DialogDraft? = null) {
        val zone = model?.zone ?: return
        val joined = DateTimePickerInput(this, getString(R.string.join_label), dateRequired = true, zone = zone, stateId = R.id.dialog_joined_input)
        val withdrew = DateTimePickerInput(this, getString(R.string.withdraw_label), zone = zone, stateId = R.id.dialog_left_input)
        val noteInput = textField(getString(R.string.membership_note_hint), id = R.id.dialog_note_input, singleLine = false, maxLength = ManualTextPolicy.MAX_NOTE_CHARS)
        val error = errorLine()
        val body = Dialogs.content(this) {
            addView(joined, lp())
            addView(withdrew, lp(top = 12))
            addView(LabeledField(context, getString(R.string.membership_note_hint), noteInput), lp(top = 12))
            addView(error, lp(top = 8))
        }
        Dialogs.showRestorable(
            Dialogs.builder(this)
                .setTitle(R.string.add_membership_history)
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, null),
            key = ADD_PERIOD_DIALOG,
            content = body,
            restored = restored,
        ) { dialog ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                val joinedBoundary = joined.boundary
                val withdrewBoundary = withdrew.boundary
                if (joinedBoundary == null || !isValidMembershipRange(joinedBoundary, withdrewBoundary)) {
                    error.showMessage(getString(R.string.invalid_date))
                    return@setOnClickListener
                }
                if (listOfNotNull(joinedBoundary, withdrewBoundary).any { !isMembershipBoundaryWithinPresent(it, zone) }) {
                    error.showMessage(getString(R.string.invalid_future_date))
                    return@setOnClickListener
                }
                button.isEnabled = false
                val noteText = noteInput.text.toString()
                work.run(
                    task = { repository.addMembershipPeriod(uid, joinedBoundary, withdrewBoundary, noteText) },
                ) { result ->
                    button.isEnabled = true
                    if (result.getOrDefault(false)) {
                        dialog.dismiss()
                        load()
                    } else {
                        error.showMessage(
                            getString(platoonDataFailureMessage(result.exceptionOrNull(), R.string.membership_period_overlap)),
                        )
                    }
                }
            }
        }
    }

    private fun boundaryText(date: LocalDate?, instant: Instant?, timeKnown: Boolean, zone: ZoneId): String {
        val displayDate = date ?: instant?.atZone(zone)?.toLocalDate() ?: return getString(R.string.unknown)
        return if (timeKnown && instant != null) {
            Labels.format(Labels.DATE_TIME, instant, zone)
        } else {
            Labels.format(Labels.DATE, displayDate)
        }
    }

    private fun firstSeen(status: MemberStatus, zone: ZoneId): String =
        Labels.format(Labels.DATE, status.firstSeenAt.atZone(zone).toLocalDate())

    private fun sourceLabel(source: EvidenceSource): String = getString(
        when (source) {
            EvidenceSource.GAME_UPDATES -> R.string.source_updates
            EvidenceSource.SNAPSHOT -> R.string.source_roster
            EvidenceSource.MANUAL -> R.string.source_manual
            EvidenceSource.LEGACY_IMPORT -> R.string.source_legacy
            EvidenceSource.ACTIVITY -> R.string.source_activity
        },
    )

    private data class Model(
        val status: MemberStatus,
        val latest: SnapshotMember?,
        val zone: ZoneId,
    )

    companion object {
        const val EXTRA_UID = "uid"
        private const val LOAD = "load"
        private const val SAVE = "save"
        private const val DELETE = "delete"
        private const val STATE_INPUTS_INITIALIZED = "member.inputs_initialized"
        private const val ADD_PERIOD_DIALOG = "membership.add"
        private const val EDIT_PERIOD_DIALOG = "membership.edit"
        private const val ARG_PERIOD_ID = "period_id"
    }
}
