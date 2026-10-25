package dev.gf2log.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import dev.gf2log.app.capture.RosterEvents
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.PlatoonMemberCsv
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonSnapshot
import dev.gf2log.app.management.SnapshotMember
import dev.gf2log.app.ui.searchField
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.MATCH
import dev.gf2log.app.ui.MenuEntry
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.SingleViewAdapter
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import java.time.LocalDateTime
import java.time.ZoneId

/** Platoon roster: search, filter, and sort members; long-press starts selection for export. */
class PlatoonActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var repository: PlatoonRepository
    private lateinit var screen: Screen
    private val work = BackgroundWork(this, "GF2Platoon")
    // Scroll restores once rows exist, not onto the loading view of a recreated screen.
    private val memberAdapter = MemberAdapter().apply {
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }
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

    /** A roster captured while this screen is showing appears without leaving it. */
    private val rosterSaved: (String) -> Unit = { storageId ->
        if (storageId == profileBinding.scope?.storageId) load()
    }

    override fun onStart() {
        super.onStart()
        RosterEvents.addListener(rosterSaved)
    }

    override fun onStop() {
        RosterEvents.removeListener(rosterSaved)
        super.onStop()
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
                    // Rows show the latest roster and time zone, which their diff does not compare.
                    val rebind = model?.let { it.latest != loaded.latest || it.zone != loaded.zone } == true
                    model = loaded
                    selected.retainAll(loaded.statuses.mapTo(mutableSetOf(), MemberStatus::uid))
                    if (selecting) setSelecting(true)
                    renderSummary(loaded)
                    renderMembers()
                    if (rebind) memberAdapter.notifyItemRangeChanged(0, memberAdapter.itemCount)
                },
                onFailure = {
                    // Search or filter changes must not bring back rows from before the failure.
                    model = null
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
                        MenuEntry(getString(R.string.add_withdrawn_member)) {
                            WithdrawnMemberDialog.show(this, work, repository, { uid ->
                                model?.statuses?.any { it.uid == uid } == true
                            }, ::load)
                        },
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

    private fun latestMembers(snapshot: PlatoonSnapshot?): Map<Long, SnapshotMember> =
        snapshot?.members.orEmpty().associateBy(SnapshotMember::uid)

    private inner class MemberAdapter : ListAdapter<MemberStatus, MemberHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MemberHolder(PlatoonMemberRow(this@PlatoonActivity))

        override fun onBindViewHolder(holder: MemberHolder, position: Int) {
            val member = getItem(position)
            val loaded = model ?: return
            holder.row.bind(
                member = member,
                latest = loaded.latestMembers[member.uid],
                zone = loaded.zone,
                selecting = selecting,
                selected = member.uid in selected,
                onClick = {
                    if (selecting) {
                        toggle(member.uid)
                    } else {
                        startActivity(
                            Intent(this@PlatoonActivity, MemberDetailActivity::class.java)
                                .putExtra(MemberDetailActivity.EXTRA_UID, member.uid),
                        )
                    }
                },
                onLongClick = { toggle(member.uid) },
            )
        }
    }

    private class MemberHolder(val row: PlatoonMemberRow) : RecyclerView.ViewHolder(row.view)


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
