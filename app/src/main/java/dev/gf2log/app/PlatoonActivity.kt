package dev.gf2log.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
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
import dev.gf2log.app.management.isValidMembershipRange
import dev.gf2log.app.settings.GameTimeZonePreferences
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class PlatoonActivity : LocalizedActivity() {
    private lateinit var repository: PlatoonRepository
    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var summary: TextView
    private lateinit var memberContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var filterSpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private var statuses = emptyList<MemberStatus>()
    private var latestMembers = emptyMap<Long, SnapshotMember>()
    private val selectedUids = linkedSetOf<Long>()
    private var pendingMemberCsv: String? = null
    private val reconciliationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2PlatoonReconciliation")
    }
    private var reconciliationGeneration = 0
    private var screenResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        val scope = profileBinding.scope ?: run {
            TransientMessage.show(this, R.string.no_platoon_detected_detail, Toast.LENGTH_LONG)
            finish()
            return
        }
        repository = PlatoonRepository(this, scope)
        setContentView(
            PrimaryNavigation.wrap(
                this,
                buildContentView(),
                PrimaryNavigation.Destination.PLATOON,
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        if (!profileBinding.isCurrent(this)) {
            recreate()
            return
        }
        screenResumed = true
        val generation = ++reconciliationGeneration
        reconciliationExecutor.execute {
            val reconciliation = runCatching { repository.reconcileRetainedCsvFiles() }
            reconciliation.exceptionOrNull()?.let { error ->
                Log.e(TAG, "Retained Platoon CSV reconciliation failed", error)
            }
            runOnUiThread {
                if (generation == reconciliationGeneration && screenResumed &&
                    !isFinishing && !isDestroyed
                ) {
                    if (reconciliation.isSuccess) {
                        refresh()
                    } else {
                        summary.setText(R.string.status_platoon_csv_import_failed)
                    }
                }
            }
        }
    }

    override fun onPause() {
        screenResumed = false
        reconciliationGeneration += 1
        super.onPause()
    }

    override fun onDestroy() {
        reconciliationExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val spacing = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            addView(TextView(context).apply {
                text = getString(R.string.platoon_management)
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(
                PlatoonProfileSelector.controls(this@PlatoonActivity),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                },
            )
            summary = TextView(context).apply {
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(6), 0, dp(8))
            }
            addView(summary, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.weekly_table)
                useFeatureActionStyle()
                setOnClickListener {
                    startActivity(Intent(this@PlatoonActivity, WeeklyReportActivity::class.java))
                }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.compare_latest_snapshots)
                useNavigationActionStyle()
                setOnClickListener {
                    startActivity(
                        Intent(this@PlatoonActivity, SnapshotComparisonActivity::class.java),
                    )
                }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.export_selected_members)
                useNavigationActionStyle()
                setOnClickListener { exportSelectedMembers() }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.add_withdrawn_member)
                useNavigationActionStyle()
                setOnClickListener { showAddWithdrawnMemberDialog() }
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
            addView(searchInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                filterSpinner = spinner(
                    listOf(
                        getString(R.string.filter_active),
                        getString(R.string.filter_all),
                    ),
                )
                sortSpinner = spinner(
                    listOf(
                        getString(R.string.sort_name),
                        getString(R.string.sort_merit),
                        getString(R.string.sort_total_merit_management),
                        getString(R.string.sort_last_login),
                    ),
                )
                addView(filterSpinner, LinearLayout.LayoutParams(0, wrap(), 1f).apply {
                    marginEnd = dp(4)
                })
                addView(sortSpinner, LinearLayout.LayoutParams(0, wrap(), 1f).apply {
                    marginStart = dp(4)
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
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
        selectedUids.retainAll(statuses.mapTo(mutableSetOf(), MemberStatus::uid))
        latestMembers = latest?.members.orEmpty().associateBy(SnapshotMember::uid)
        val active = statuses.count(MemberStatus::isActive)
        val departed = statuses.size - active
        summary.text = if (latest == null) {
            getString(R.string.no_platoon_snapshot)
        } else {
            val zone = GameTimeZonePreferences.get(this@PlatoonActivity)
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
                0 -> status.isActive
                else -> true
            }
            val matchesQuery = query.isBlank() ||
                status.name.contains(query, ignoreCase = true) ||
                status.uid.toString().contains(query)
            matchesFilter && matchesQuery
        }
        val sorted = when (sortSpinner.selectedItemPosition) {
            1 -> filtered.sortedByDescending { latestMembers[it.uid]?.weeklyMerit ?: -1 }
            2 -> filtered.sortedByDescending { latestMembers[it.uid]?.totalMerit ?: -1 }
            3 -> filtered.sortedByDescending { latestMembers[it.uid]?.lastLogin ?: 0 }
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
                }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(8)
                })
                addView(ModernUi.actionRow(
                    context = context,
                    title = buildString {
                        append(status.name)
                        append(" · ")
                        append(
                            getString(
                                if (status.isActive) R.string.active_member else R.string.departed_member,
                            ),
                        )
                    },
                    detail = buildString {
                        append("#")
                        append(status.uid)
                        if (latest != null) {
                            append(" · ")
                            append(getString(R.string.merit_this_week))
                            append(" ")
                            append(latest.weeklyMerit)
                            append(" · ")
                            append(getString(R.string.total_merit))
                            append(" ")
                            append(latest.totalMerit)
                        }
                    },
                    titleMaxLines = 2,
                    onClick = {
                        startActivity(
                            Intent(this@PlatoonActivity, MemberDetailActivity::class.java)
                                .putExtra(MemberDetailActivity.EXTRA_UID, status.uid),
                        )
                    },
                ), LinearLayout.LayoutParams(0, wrap(), 1f))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
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
        TransientMessage.show(
            this,
            getString(if (exported) R.string.members_exported else R.string.status_export_failed),
            Toast.LENGTH_SHORT,
        )
    }

    @Suppress("DEPRECATION")
    private fun exportSelectedMembers() {
        val selected = statuses.filter { it.uid in selectedUids }
        if (selected.isEmpty()) {
            TransientMessage.show(this, R.string.select_members_first)
            return
        }
        pendingMemberCsv = PlatoonMemberCsv.format(
            statuses = selected,
            latestMembers = latestMembers,
            zoneId = GameTimeZonePreferences.get(this),
        )
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "GF2logger-members.csv")
        startActivityForResult(intent, REQUEST_EXPORT_MEMBERS)
    }

    private fun showAddWithdrawnMemberDialog() {
        val uidInput = EditText(this).apply {
            hint = getString(R.string.uid)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val nicknameInput = EditText(this).apply {
            hint = getString(R.string.member_nickname)
            setSingleLine(true)
        }
        val joined = DateTimePickerInput(
            this,
            getString(R.string.join_field),
            dateRequired = true,
            zone = GameTimeZonePreferences.get(this),
        )
        val withdrew = DateTimePickerInput(
            this,
            getString(R.string.withdraw_field),
            dateRequired = true,
            zone = GameTimeZonePreferences.get(this),
        )
        val noteInput = EditText(this).apply {
            hint = getString(R.string.membership_note_hint)
            minLines = 2
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(uidInput, matchWidth())
            addView(nicknameInput, matchWidth())
            addView(joined, matchWidth())
            addView(withdrew, matchWidth())
            addView(noteInput, matchWidth())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_withdrawn_member)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val uid = uidInput.text.toString().trim().toLongOrNull()
                val nickname = nicknameInput.text.toString().trim()
                val joinedBoundary = joined.boundary
                val withdrewBoundary = withdrew.boundary
                val validRange = joinedBoundary != null &&
                    withdrewBoundary != null &&
                    isValidMembershipRange(joinedBoundary, withdrewBoundary)
                val saved = uid != null && uid > 0 && nickname.isNotBlank() && validRange &&
                    runCatching {
                        repository.addWithdrawnMember(
                            uid,
                            nickname,
                            requireNotNull(joinedBoundary),
                            requireNotNull(withdrewBoundary),
                            noteInput.text.toString(),
                        )
                    }.getOrDefault(false)
                if (saved) {
                    dialog.dismiss()
                    refresh()
                    TransientMessage.show(this, R.string.saved)
                } else {
                    TransientMessage.show(
                        this,
                        R.string.invalid_withdrawn_member,
                        Toast.LENGTH_SHORT,
                    )
                }
            }
        }
        dialog.show()
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
        private const val TAG = "GF2Platoon"
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")
        private const val REQUEST_EXPORT_MEMBERS = 301
    }
}
