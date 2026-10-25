package dev.gf2log.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import dev.gf2log.app.capture.CaptureSnapshot
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.capture.format
import dev.gf2log.app.management.CsvImportCheckpointManager
import dev.gf2log.app.management.PlatoonCsvImportStore
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.SnapshotComparison
import dev.gf2log.app.standby.ScheduledRecording
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.CaptureGuard
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.captureActive
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.setShownInGroup
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.text
import dev.gf2log.protocol.GuildMembersCsv
import java.time.ZoneId

/**
 * Home: capture state and actions first, then the selected Platoon, data tools, and recent
 * parsed-packet evidence. Capture state is observed through [CaptureStatus] listeners.
 */
class MainActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var screen: Screen
    private val work = BackgroundWork(this, "GF2Home")
    private lateinit var csvImport: CsvImportFlow
    private lateinit var packetHistory: HomePacketHistory
    private lateinit var standbyFlow: StandbyFlow

    private lateinit var capturePanel: HomeCapturePanel
    private lateinit var undoRow: View
    private lateinit var progressBanner: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var rosterSummary: LinearLayout

    private var captureOnceRequested = false

    /** A start is waiting on the permission or VPN consent screens; further taps are ignored. */
    private var startPending = false

    private val statusListener: (CaptureSnapshot) -> Unit = { capturePanel.render(it) }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        startPending = false
        if (result.resultCode == RESULT_OK) startCaptureService() else CaptureStatus.markStopped()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnAndStart()
    }

    private val exportRoster = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val scope = profileBinding.scope ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        work.run(
            task = {
                val latest = requireNotNull(PlatoonCsvImportStore.latestRetainedFile(scope.retainedCsvDirectory(this)))
                val snapshot = requireNotNull(GuildMembersCsv.parse(latest.readText(Charsets.UTF_8))) {
                    "Stored Platoon CSV is invalid"
                }
                DocumentAccess.openOutput(contentResolver, uri).writer(Charsets.UTF_8).use {
                    it.write(GuildMembersCsv.formatForSpreadsheet(snapshot))
                }
            },
        ) { result ->
            Feedback.show(this, if (result.isSuccess) R.string.roster_csv_exported else R.string.status_export_failed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OnboardingPreferences.isCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        profileBinding = ActivePlatoonScopeBinding(this)
        packetHistory = HomePacketHistory(this, work, profileBinding.scope)
        packetHistory.restoreState(savedInstanceState)
        csvImport = CsvImportFlow(this, work, { profileBinding.scope }, ::showProgress)
        csvImport.restoreState(savedInstanceState)
        standbyFlow = StandbyFlow(this, work)
        captureOnceRequested = savedInstanceState?.getBoolean(STATE_CAPTURE_ONCE) == true
        screen = Screen(this, PrimaryNavigation.Destination.HOME)
        screen.topBar
            .setLeading(ImageView(this).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(36))
            })
            .setTitle(getString(R.string.app_name))
            .setActions(listOf(
                BarAction(R.drawable.ic_bedtime, getString(R.string.open_standby_mode)) { standbyFlow.start() },
                BarAction(R.drawable.ic_settings, getString(R.string.open_options)) {
                    startActivity(Intent(this, OptionsActivity::class.java))
                },
            ))
        screen.setBody(Screen.scrollingBody(this, buildContent()))
    }

    override fun onStart() {
        super.onStart()
        if (::capturePanel.isInitialized) CaptureStatus.addListener(statusListener)
    }

    override fun onStop() {
        CaptureStatus.removeListener(statusListener)
        super.onStop()
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) {
            recreate()
            return
        }
        capturePanel.renderClients()
        packetHistory.load()
        loadRosterSummary()
        loadUndoAvailability()
        csvImport.onScreenReady()
        standbyFlow.showUnseenResults()
        // Revoking exact alarms cancels the next wake-up without telling the app; plan it again.
        if (ScheduledRecording.isEnabled(this)) ScheduledRecording.reschedule(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::csvImport.isInitialized) csvImport.saveState(outState)
        if (::packetHistory.isInitialized) packetHistory.saveState(outState)
        outState.putBoolean(STATE_CAPTURE_ONCE, captureOnceRequested)
        super.onSaveInstanceState(outState)
    }

    private fun buildContent(): LinearLayout = column {
        progressText = text(null, TextRole.SECONDARY) { setTextColor(getColor(R.color.accent_text)) }
        progressBanner = panel(emphasized = true, padding = 12) {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(row {
                addView(ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                }, lp(20, 20, end = 12))
                addView(progressText, lp(width = 0, weight = 1f))
            }, lp())
        }
        addView(progressBanner, lp(top = 4, bottom = 8))
        capturePanel = HomeCapturePanel(this@MainActivity, ::beginCapture) { CaptureGuard.stop(this@MainActivity) }
        addView(capturePanel.view, lp(top = 4))
        addView(sectionHeader(getString(R.string.active_platoon_section)), lp())
        addView(PlatoonProfileSelector.card(this@MainActivity), lp())
        rosterSummary = column { visibility = View.GONE }
        addView(rosterSummary, lp(top = 8))
        addView(sectionHeader(getString(R.string.data_tools)), lp())
        addView(group(listOf(
            navigationRow(getString(R.string.import_platoon_csv), getString(R.string.import_platoon_csv_detail), R.drawable.ic_upload) {
                csvImport.start()
            },
            navigationRow(getString(R.string.undo_last_csv_import), getString(R.string.undo_last_csv_import_detail), R.drawable.ic_undo) {
                csvImport.undo()
            }.also { undoRow = it },
            navigationRow(
                getString(R.string.export_latest_platoon_csv),
                getString(R.string.export_latest_platoon_csv_detail),
                R.drawable.ic_download,
            ) { exportLatestRoster() },
            navigationRow(getString(R.string.backup_and_restore), getString(R.string.backup_and_restore_detail), R.drawable.ic_storage) {
                startActivity(Intent(this@MainActivity, OptionsActivity::class.java))
            },
        )), lp())
        addView(packetHistory.view, lp())
        undoRow.setShownInGroup(false)
    }

    // region Capture

    private fun beginCapture(once: Boolean) {
        if (startPending || captureActive) return
        startPending = true
        captureOnceRequested = once
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Capture works without it, but the foreground notification is the only stop control
            // outside the app, so ask at the moment it becomes relevant.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnAndStart()
        }
    }

    private fun requestVpnAndStart() {
        val consent = VpnService.prepare(this)
        if (consent == null) startCaptureService() else vpnConsent.launch(consent)
    }

    private fun startCaptureService() {
        CaptureStatus.markStarting()
        startForegroundService(
            Intent(this, CaptureVpnService::class.java)
                .setAction(CaptureVpnService.ACTION_START)
                .putExtra(CaptureVpnService.EXTRA_CAPTURE_ONCE, captureOnceRequested),
        )
        captureOnceRequested = false
        startPending = false
    }

    // endregion


    // region Data tools

    private fun showProgress(message: CharSequence?) {
        progressBanner.visibility = if (message == null) View.GONE else View.VISIBLE
        progressText.text = message
        if (message == null) {
            // An import or undo just finished: the roster and the undo checkpoint may have changed.
            loadRosterSummary()
            loadUndoAvailability()
        }
    }

    /** The undo row only appears while a CSV import checkpoint exists. */
    private fun loadUndoAvailability() {
        val scope = profileBinding.scope ?: return
        work.run(key = UNDO, task = { CsvImportCheckpointManager(this, scope).canUndo() }) { result ->
            undoRow.setShownInGroup(result.getOrDefault(false))
        }
    }

    private fun exportLatestRoster() {
        val scope = profileBinding.scope ?: run {
            Feedback.show(this, R.string.no_platoon_detected_detail, long = true)
            return
        }
        work.run(task = { PlatoonCsvImportStore.latestRetainedFile(scope.retainedCsvDirectory(this))?.name }) { result ->
            val name = result.getOrNull()
            if (name == null) Feedback.show(this, R.string.status_no_platoon_csv) else exportRoster.launch(name)
        }
    }

    // endregion

    /** The latest roster change, so a leader sees what the last session captured without leaving Home. */
    private fun loadRosterSummary() {
        val scope = profileBinding.scope ?: return
        work.run(
            key = SUMMARY,
            task = {
                val snapshots = PlatoonRepository(this, scope).listSnapshots(2)
                val latest = snapshots.firstOrNull()
                latest?.let { it to snapshots.getOrNull(1)?.let { older -> SnapshotComparison.compare(older, it) } }
            },
        ) { result ->
            val summary = result.getOrNull()
            if (summary == null) {
                rosterSummary.visibility = View.GONE
                return@run
            }
            val (latest, comparison) = summary
            rosterSummary.removeAllViews()
            rosterSummary.visibility = View.VISIBLE
            val detail = buildList {
                add(resources.getQuantityString(R.plurals.member_count, latest.members.size, latest.members.size))
                comparison?.let {
                    add(getString(R.string.roster_joined_count, it.joined.size))
                    add(getString(R.string.roster_withdrew_count, it.left.size))
                }
            }.joinToString(" · ")
            rosterSummary.addView(group(listOf(
                navigationRow(
                    getString(R.string.latest_roster_title, Labels.format(Labels.DATE_TIME, latest.capturedAt, ZoneId.systemDefault())),
                    detail,
                    R.drawable.ic_compare,
                    enabled = comparison != null,
                    showChevron = comparison != null,
                ) { startActivity(Intent(this, SnapshotComparisonActivity::class.java)) },
            )), lp())
        }
    }

    private companion object {
        const val SUMMARY = "summary"
        const val UNDO = "undo"
        const val STATE_CAPTURE_ONCE = "home.capture_once"
    }
}
