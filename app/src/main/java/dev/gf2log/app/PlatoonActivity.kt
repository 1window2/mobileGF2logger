package dev.gf2log.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonMemberCsv
import dev.gf2log.app.management.SnapshotMember
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PlatoonActivity : LocalizedActivity() {
    private lateinit var repository: PlatoonRepository
    private lateinit var summary: TextView
    private lateinit var memberContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var filterSpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private var statuses = emptyList<MemberStatus>()
    private var latestMembers = emptyMap<Long, SnapshotMember>()
    private val selectedUids = linkedSetOf<Long>()
    private var pendingMemberCsv: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = PlatoonRepository(this)
        repository.importLegacyCsvFiles(
            File(filesDir, dev.gf2log.app.capture.GuildMembersCsvWriter.OUTPUT_DIRECTORY),
        )
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildContentView(): ScrollView {
        val spacing = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            addView(TextView(context).apply {
                text = getString(R.string.platoon_management)
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            summary = TextView(context).apply {
                textSize = 15f
                setPadding(0, dp(8), 0, dp(8))
            }
            addView(summary, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.weekly_table)
                setOnClickListener {
                    startActivity(Intent(this@PlatoonActivity, WeeklyReportActivity::class.java))
                }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.compare_latest_snapshots)
                setOnClickListener {
                    startActivity(
                        Intent(this@PlatoonActivity, SnapshotComparisonActivity::class.java),
                    )
                }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.export_selected_members)
                setOnClickListener { exportSelectedMembers() }
            }, matchWidth())
            searchInput = EditText(context).apply {
                hint = getString(R.string.search_members)
                setSingleLine(true)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        renderMembers()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            addView(searchInput, matchWidth())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                filterSpinner = spinner(
                    listOf(
                        getString(R.string.filter_all),
                        getString(R.string.filter_active),
                        getString(R.string.filter_departed),
                    ),
                )
                sortSpinner = spinner(
                    listOf(
                        getString(R.string.sort_name),
                        getString(R.string.sort_merit),
                        getString(R.string.sort_last_login),
                    ),
                )
                addView(filterSpinner, LinearLayout.LayoutParams(0, wrap(), 1f))
                addView(sortSpinner, LinearLayout.LayoutParams(0, wrap(), 1f))
            }, matchWidth())
            filterSpinner.onItemSelectedListener = SimpleItemSelectedListener { renderMembers() }
            sortSpinner.onItemSelectedListener = SimpleItemSelectedListener { renderMembers() }
            memberContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(memberContainer, matchWidth())
        }
        return ScrollView(this).apply { addView(container, matchWidth()) }
    }

    private fun refresh() {
        val snapshots = repository.listSnapshots(1)
        val latest = snapshots.firstOrNull()
        statuses = repository.listMemberStatuses()
        latestMembers = latest?.members.orEmpty().associateBy(SnapshotMember::uid)
        val active = statuses.count(MemberStatus::isActive)
        val departed = statuses.size - active
        summary.text = if (latest == null) {
            getString(R.string.no_platoon_snapshot)
        } else {
            val zone = ZoneId.systemDefault()
            getString(
                R.string.platoon_summary,
                active,
                departed,
                DISPLAY_TIME.format(latest.capturedAt.atZone(zone)),
                zone.id,
            )
        }
        renderMembers()
    }

    private fun renderMembers() {
        if (!::memberContainer.isInitialized) return
        val query = searchInput.text.toString().trim()
        val filtered = statuses.filter { status ->
            val matchesFilter = when (filterSpinner.selectedItemPosition) {
                1 -> status.isActive
                2 -> !status.isActive
                else -> true
            }
            val matchesQuery = query.isBlank() ||
                status.name.contains(query, ignoreCase = true) ||
                status.uid.toString().contains(query)
            matchesFilter && matchesQuery
        }
        val sorted = when (sortSpinner.selectedItemPosition) {
            1 -> filtered.sortedByDescending { latestMembers[it.uid]?.weeklyMerit ?: -1 }
            2 -> filtered.sortedByDescending { latestMembers[it.uid]?.lastLogin ?: 0 }
            else -> filtered.sortedWith(
                compareByDescending<MemberStatus>(MemberStatus::isActive)
                    .thenBy(String.CASE_INSENSITIVE_ORDER, MemberStatus::name),
            )
        }

        memberContainer.removeAllViews()
        if (sorted.isEmpty()) {
            memberContainer.addView(TextView(this).apply {
                text = getString(R.string.no_matching_members)
                setPadding(0, dp(16), 0, dp(16))
            }, matchWidth())
            return
        }
        sorted.forEach { status ->
            val latest = latestMembers[status.uid]
            memberContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(CheckBox(context).apply {
                    isChecked = status.uid in selectedUids
                    contentDescription = getString(R.string.select_member, status.name)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedUids += status.uid else selectedUids -= status.uid
                    }
                }, LinearLayout.LayoutParams(wrap(), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(Button(context).apply {
                    isAllCaps = false
                    text = buildString {
                        append(if (status.isActive) "● " else "○ ")
                        append(status.name)
                        append("  #")
                        append(status.uid)
                        if (latest != null) {
                            append("\n")
                            append(getString(R.string.merit_this_week))
                            append(": ")
                            append(latest.weeklyMerit)
                            append(" · ")
                            append(getString(R.string.total_merit))
                            append(": ")
                            append(latest.totalMerit)
                        }
                    }
                    setOnClickListener {
                        startActivity(
                            Intent(this@PlatoonActivity, MemberDetailActivity::class.java)
                                .putExtra(MemberDetailActivity.EXTRA_UID, status.uid),
                        )
                    }
                }, LinearLayout.LayoutParams(0, wrap(), 1f))
            }, matchWidth())
        }
    }

    @Deprecated("Uses the platform document picker without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_MEMBERS) return
        val content = pendingMemberCsv
        pendingMemberCsv = null
        val destination = data?.data
        if (resultCode != RESULT_OK || destination == null || content == null) return
        val exported = runCatching {
            val output = TrustedExportDestination.openOutputStream(contentResolver, destination)
                ?: error("Document provider did not open an output stream")
            output.writer(Charsets.UTF_8).use { it.write(content) }
        }.isSuccess
        Toast.makeText(
            this,
            getString(if (exported) R.string.members_exported else R.string.status_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    @Suppress("DEPRECATION")
    private fun exportSelectedMembers() {
        val selected = statuses.filter { it.uid in selectedUids }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_members_first, Toast.LENGTH_SHORT).show()
            return
        }
        pendingMemberCsv = PlatoonMemberCsv.format(
            statuses = selected,
            latestMembers = latestMembers,
            zoneId = ZoneId.systemDefault(),
        )
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "GF2logger-members.csv")
        startActivityForResult(intent, REQUEST_EXPORT_MEMBERS)
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@PlatoonActivity,
            android.R.layout.simple_spinner_dropdown_item,
            items,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")
        private const val REQUEST_EXPORT_MEMBERS = 301
    }
}
