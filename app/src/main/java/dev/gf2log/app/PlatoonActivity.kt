package dev.gf2log.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.PlatoonMemberCsv
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonSnapshot
import dev.gf2log.app.management.SnapshotMember
import dev.gf2log.app.management.isValidMembershipRange
import dev.gf2log.app.ui.searchField
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.AccessibleRow
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.LabeledField
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.MATCH
import dev.gf2log.app.ui.MenuEntry
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.SingleViewAdapter
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.errorLine
import dev.gf2log.app.ui.showMessage
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Platoon roster: search, filter, and sort members; long-press starts selection for export. */
class PlatoonActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var repository: PlatoonRepository
    private lateinit var screen: Screen
    private val work = BackgroundWork(this, "GF2Platoon")
    private val memberAdapter = MemberAdapter()
    private val stateAdapter = SingleViewAdapter()
    private lateinit var summary: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var sortButton: TextView
    private lateinit var resultCount: TextView

    private var model: Model? = null
    private var filter = Filter.ACTIVE
    private var sort = Sort.NAME
    private var selecting = false
    private val selected = linkedSetOf<Long>()
    private var pendingExport: LongArray? = null

    private val exportMembers = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val uids = pendingExport?.toSet()
        pendingExport = null
        if (uri == null || uids.isNullOrEmpty()) return@registerForActivityResult
        work.run(
            task = {
                val statuses = repository.listMemberStatuses().filter { it.uid in uids }
                val latest = latestMembers(repository.listSnapshots(1).firstOrNull())
                val csv = PlatoonMemberCsv.format(statuses, latest, repository.gameZone())
                DocumentAccess.openOutput(contentResolver, uri).writer(Charsets.UTF_8).use { it.write(csv) }
            },
        ) { result ->
            Feedback.show(this, if (result.isSuccess) R.string.members_exported else R.string.status_export_failed)
            if (result.isSuccess) setSelecting(false)
        }
    }

    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        val scope = profileBinding.scope ?: run {
            Feedback.show(this, R.string.no_platoon_detected_detail, long = true)
            finish()
            return
        }
        repository = PlatoonRepository(this, scope)
        savedInstanceState?.let { state ->
            filter = Filter.entries.getOrElse(state.getInt(STATE_FILTER)) { Filter.ACTIVE }
            sort = Sort.entries.getOrElse(state.getInt(STATE_SORT)) { Sort.NAME }
            selecting = state.getBoolean(STATE_SELECTING)
            state.getLongArray(STATE_SELECTED)?.let { selected += it.toList() }
            pendingExport = state.getLongArray(STATE_PENDING_EXPORT)
        }
        onBackPressedDispatcher.addCallback(this, exitSelection)
        screen = Screen(this, PrimaryNavigation.Destination.PLATOON)
        val header = buildHeader(savedInstanceState?.getString(STATE_QUERY).orEmpty())
        screen.setBody(RecyclerView(this).apply {
            id = R.id.screen_list
            layoutManager = LinearLayoutManager(context)
            adapter = ConcatAdapter(SingleViewAdapter(header), memberAdapter, stateAdapter)
            itemAnimator = null
            setPadding(dp(16), 0, dp(16), dp(24))
        })
        setSelecting(selecting)
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) {
            recreate()
            return
        }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_FILTER, filter.ordinal)
        outState.putInt(STATE_SORT, sort.ordinal)
        outState.putBoolean(STATE_SELECTING, selecting)
        outState.putLongArray(STATE_SELECTED, selected.toLongArray())
        if (::searchInput.isInitialized) outState.putString(STATE_QUERY, searchInput.text.toString())
        pendingExport?.let { outState.putLongArray(STATE_PENDING_EXPORT, it) }
        super.onSaveInstanceState(outState)
    }

    private fun load() {
        if (model == null) stateAdapter.view = StateViews.loading(this, getString(R.string.loading_members))
        work.run(
            key = LOAD,
            task = {
                repository.reconcileRetainedCsvFiles()
                val latest = repository.listSnapshots(1).firstOrNull()
                Model(
                    latest = latest,
                    statuses = repository.listMemberStatuses(),
                    latestMembers = latestMembers(latest),
                    zone = repository.gameZone(),
                )
            },
        ) { result ->
            result.fold(
                onSuccess = { loaded ->
                    model = loaded
                    selected.retainAll(loaded.statuses.mapTo(mutableSetOf(), MemberStatus::uid))
                    if (selecting) setSelecting(true)
                    renderSummary(loaded)
                    renderMembers()
                },
                onFailure = {
                    memberAdapter.submitList(emptyList())
                    stateAdapter.view = StateViews.failure(this, getString(R.string.platoon_load_failed)) { load() }
                },
            )
        }
    }

    private fun buildHeader(query: String): View {
        val search = searchField(getString(R.string.search_members), R.id.platoon_search_input, query) { renderMembers() }
        searchInput = search.findViewById(R.id.platoon_search_input)
        sortButton = text(null, TextRole.LABEL) {
            setTextColor(getColor(R.color.text_primary))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            background = selectableRect()
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sort, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            compoundDrawableTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            setOnClickListener { chooseSort() }
        }
        resultCount = text(null, TextRole.LABEL) {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        summary = row(Gravity.TOP)
        updateSortLabel()
        return column {
            addView(PlatoonProfileSelector.card(this@PlatoonActivity), lp(top = 4))
            addView(summary, lp(top = 12))
            addView(search, lp(top = 16))
            addView(Controls.segmentedControl(
                context = this@PlatoonActivity,
                options = Filter.entries.map { it.name to getString(it.label) },
                selectedValue = filter.name,
            ) { value ->
                filter = Filter.valueOf(value)
                renderMembers()
            }, lp(top = 8))
            addView(row {
                addView(resultCount, lp(width = 0, weight = 1f, start = 4))
                addView(sortButton, lp(WRAP, WRAP))
            }, lp(top = 4, bottom = 4))
        }
    }

    private fun renderSummary(loaded: Model) {
        summary.removeAllViews()
        val active = loaded.statuses.count(MemberStatus::isActive)
        val tiles = listOf(
            getString(R.string.stat_active) to active.toString(),
            getString(R.string.stat_withdrawn) to (loaded.statuses.size - active).toString(),
            getString(R.string.stat_last_snapshot) to (loaded.latest?.let {
                Labels.format(Labels.DATE_TIME, it.capturedAt, ZoneId.systemDefault())
            } ?: getString(R.string.none)),
        )
        tiles.forEachIndexed { index, (label, value) ->
            summary.addView(panel(padding = 12) {
                isFocusable = true
                contentDescription = Labels.labeled(context, label, value)
                addView(text(label, TextRole.CAPTION) {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, lp())
                addView(text(value, if (index < 2) TextRole.HEADLINE else TextRole.BODY) {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    maxLines = 2
                }, lp(top = 2))
            }, lp(width = 0, height = MATCH, weight = if (index < 2) 1f else 1.6f, start = if (index == 0) 0 else 8))
        }
    }

    private fun renderMembers() {
        val loaded = model ?: return
        val query = searchInput.text.toString().trim()
        val visible = loaded.statuses
            .filter { status ->
                when (filter) {
                    Filter.ACTIVE -> status.isActive
                    Filter.WITHDRAWN -> !status.isActive
                    Filter.ALL -> true
                }
            }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.uid.toString().contains(query) }
            .let { sorted(it, loaded) }
        resultCount.text = resources.getQuantityString(R.plurals.member_count, visible.size, visible.size)
        stateAdapter.view = when {
            visible.isNotEmpty() -> null
            loaded.statuses.isEmpty() -> StateViews.empty(
                this,
                R.drawable.ic_group,
                getString(R.string.no_platoon_snapshot),
                getString(R.string.no_platoon_snapshot_detail),
            )
            else -> StateViews.empty(this, R.drawable.ic_search, getString(R.string.no_matching_members))
        }
        memberAdapter.submitList(visible)
    }

    private fun sorted(statuses: List<MemberStatus>, loaded: Model): List<MemberStatus> = when (sort) {
        Sort.NAME -> statuses.sortedWith(
            compareByDescending<MemberStatus>(MemberStatus::isActive)
                .thenBy(String.CASE_INSENSITIVE_ORDER, MemberStatus::name),
        )
        Sort.WEEKLY_MERIT -> statuses.sortedByDescending { loaded.latestMembers[it.uid]?.weeklyMerit ?: -1 }
        Sort.TOTAL_MERIT -> statuses.sortedByDescending { loaded.latestMembers[it.uid]?.totalMerit ?: -1 }
        Sort.LAST_LOGIN -> statuses.sortedByDescending { loaded.latestMembers[it.uid]?.lastLogin ?: 0 }
    }

    private fun chooseSort() {
        Dialogs.choose(this, getString(R.string.sort_by), Sort.entries.map { getString(it.label) }, sort.ordinal) {
            sort = Sort.entries[it]
            updateSortLabel()
            renderMembers()
        }
    }

    private fun updateSortLabel() {
        sortButton.text = getString(sort.label)
        sortButton.contentDescription = Labels.labeled(this, getString(R.string.sort_by), getString(sort.label))
    }

    private fun setSelecting(enabled: Boolean) {
        selecting = enabled
        if (!enabled) selected.clear()
        exitSelection.isEnabled = enabled
        val bar = screen.topBar
        if (enabled) {
            bar.setNavigationIcon(R.drawable.ic_close, getString(R.string.cancel_selection))
                .setNavigation { setSelecting(false) }
                .setTitle(resources.getQuantityString(R.plurals.selected_count, selected.size, selected.size))
                .setActions(
                    listOf(
                        BarAction(R.drawable.ic_check_circle, getString(R.string.select_all)) { selectAllVisible() },
                        BarAction(R.drawable.ic_download, getString(R.string.export_selected_members), selected.isNotEmpty()) {
                            exportSelected()
                        },
                    ),
                )
        } else {
            bar.setNavigation(null)
                .setTitle(getString(R.string.platoon_management))
                .setActions(
                    listOf(BarAction(R.drawable.ic_compare, getString(R.string.compare_latest_snapshots)) {
                        startActivity(Intent(this, SnapshotComparisonActivity::class.java))
                    }),
                    overflow = listOf(
                        MenuEntry(getString(R.string.select_members)) { setSelecting(true) },
                        MenuEntry(getString(R.string.add_withdrawn_member)) { showAddWithdrawnMemberDialog() },
                        MenuEntry(getString(R.string.edit_member_order)) {
                            startActivity(Intent(this, MemberOrderActivity::class.java))
                        },
                    ),
                )
        }
        memberAdapter.notifyItemRangeChanged(0, memberAdapter.itemCount)
    }

    private fun toggle(uid: Long) {
        if (!selected.add(uid)) selected.remove(uid)
        setSelecting(true)
    }

    private fun selectAllVisible() {
        val visible = memberAdapter.currentList.map(MemberStatus::uid)
        if (selected.containsAll(visible)) selected.removeAll(visible.toSet()) else selected += visible
        setSelecting(true)
    }

    private fun exportSelected() {
        if (selected.isEmpty()) {
            Feedback.show(this, R.string.select_members_first)
            return
        }
        pendingExport = selected.toLongArray()
        exportMembers.launch("GF2logger-members-${Labels.FILE_STAMP.format(LocalDateTime.now())}.csv")
    }

    private fun showAddWithdrawnMemberDialog() {
        val zone = repository.gameZone()
        val uid = LabeledField(
            this,
            getString(R.string.uid),
            textField(getString(R.string.uid_hint), inputType = InputType.TYPE_CLASS_NUMBER),
        )
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
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                val uidValue = uid.input.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }
                val name = nickname.input.text.toString().trim()
                val joinedBoundary = joined.boundary
                val withdrewBoundary = withdrew.boundary
                val problem = when {
                    uidValue == null -> R.string.invalid_uid
                    model?.statuses?.any { it.uid == uidValue } == true -> R.string.duplicate_uid
                    name.isEmpty() -> R.string.invalid_nickname
                    joinedBoundary == null || withdrewBoundary == null -> R.string.invalid_dates_required
                    !isValidMembershipRange(joinedBoundary, withdrewBoundary) -> R.string.invalid_date_order
                    else -> null
                }
                if (problem != null) {
                    error.showMessage(getString(problem))
                    return@setOnClickListener
                }
                button.isEnabled = false
                val noteText = note.input.text.toString()
                work.run(
                    task = {
                        repository.addWithdrawnMember(
                            requireNotNull(uidValue),
                            name,
                            requireNotNull(joinedBoundary),
                            requireNotNull(withdrewBoundary),
                            noteText,
                        )
                    },
                ) { result ->
                    button.isEnabled = true
                    if (result.getOrDefault(false)) {
                        dialog.dismiss()
                        Feedback.show(this, R.string.saved)
                        load()
                    } else {
                        error.showMessage(getString(R.string.invalid_withdrawn_member))
                    }
                }
            }
        }
    }

    private fun latestMembers(snapshot: PlatoonSnapshot?): Map<Long, SnapshotMember> =
        snapshot?.members.orEmpty().associateBy(SnapshotMember::uid)

    private inner class MemberAdapter : ListAdapter<MemberStatus, MemberHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MemberHolder()

        override fun onBindViewHolder(holder: MemberHolder, position: Int) = holder.bind(getItem(position))
    }

    private inner class MemberHolder : RecyclerView.ViewHolder(AccessibleRow(this@PlatoonActivity)) {
        private val row = itemView as AccessibleRow
        private val check = CheckBox(this@PlatoonActivity).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            buttonTintList = Controls.controlTint(this@PlatoonActivity)
        }
        private val name = text(null, TextRole.SUBTITLE) {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val status = badge("")
        private val detail = text(null, TextRole.CAPTION) {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val merit = text(null, TextRole.SUBTITLE) { gravity = Gravity.END }

        init {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = dp(64)
            row.setPadding(dp(8), dp(8), dp(8), dp(8))
            row.background = selectableRect()
            row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) }
            row.addView(check, lp(WRAP, WRAP, end = 4))
            row.addView(column {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(row {
                    addView(name, lp(width = 0, weight = 1f))
                    addView(status, lp(WRAP, WRAP, start = 8))
                }, lp())
                addView(detail, lp(top = 2))
            }, lp(width = 0, weight = 1f))
            row.addView(column {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(merit, lp())
                addView(text(getString(R.string.merit_this_week_short), TextRole.CAPTION) { gravity = Gravity.END }, lp())
            }, lp(WRAP, WRAP, start = 12))
            row.addView(icon(R.drawable.ic_chevron_right, sizeDp = 20), lp(20, 20, start = 4))
        }

        fun bind(member: MemberStatus) {
            val loaded = model ?: return
            val latest = loaded.latestMembers[member.uid]
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.isChecked = member.uid in selected
            name.text = member.name
            status.text = getString(if (member.isActive) R.string.active_member else R.string.departed_member)
            status.visibility = if (member.isActive) View.GONE else View.VISIBLE
            detail.text = buildList {
                add(Labels.uid(this@PlatoonActivity, member.uid))
                add(getString(R.string.level_short, latest?.level ?: member.level))
                latest?.lastLogin?.takeIf { it > 0 }?.let {
                    add(getString(
                        R.string.last_login_short,
                        Labels.format(Labels.DATE_TIME, Instant.ofEpochSecond(it), loaded.zone),
                    ))
                }
            }.joinToString(" · ")
            merit.text = latest?.weeklyMerit?.toString() ?: "—"
            row.isSelected = selecting && member.uid in selected
            row.contentDescription = listOfNotNull(
                getString(R.string.selected).takeIf { row.isSelected },
                member.name,
                status.text,
                detail.text,
                Labels.labeled(this@PlatoonActivity, getString(R.string.merit_this_week), merit.text),
            ).joinToString(". ")
            row.setOnClickListener {
                if (selecting) {
                    toggle(member.uid)
                } else {
                    startActivity(
                        Intent(this@PlatoonActivity, MemberDetailActivity::class.java)
                            .putExtra(MemberDetailActivity.EXTRA_UID, member.uid),
                    )
                }
            }
            row.setOnLongClickListener {
                toggle(member.uid)
                true
            }
        }
    }

    private data class Model(
        val latest: PlatoonSnapshot?,
        val statuses: List<MemberStatus>,
        val latestMembers: Map<Long, SnapshotMember>,
        val zone: ZoneId,
    )

    private enum class Filter(val label: Int) {
        ACTIVE(R.string.filter_active),
        WITHDRAWN(R.string.filter_withdrawn),
        ALL(R.string.filter_everyone),
    }

    private enum class Sort(val label: Int) {
        NAME(R.string.sort_name),
        WEEKLY_MERIT(R.string.sort_merit),
        TOTAL_MERIT(R.string.sort_total_merit_management),
        LAST_LOGIN(R.string.sort_last_login),
    }

    private companion object {
        const val LOAD = "load"
        const val STATE_FILTER = "platoon.filter"
        const val STATE_SORT = "platoon.sort"
        const val STATE_SELECTING = "platoon.selecting"
        const val STATE_SELECTED = "platoon.selected"
        const val STATE_QUERY = "platoon.query"
        const val STATE_PENDING_EXPORT = "platoon.pending_export"

        val DIFF = object : DiffUtil.ItemCallback<MemberStatus>() {
            override fun areItemsTheSame(oldItem: MemberStatus, newItem: MemberStatus) = oldItem.uid == newItem.uid
            override fun areContentsTheSame(oldItem: MemberStatus, newItem: MemberStatus) = oldItem == newItem
        }
    }
}
