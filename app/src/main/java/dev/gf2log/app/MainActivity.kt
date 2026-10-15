package dev.gf2log.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import dev.gf2log.app.capture.CaptureNotice
import dev.gf2log.app.capture.CapturePhase
import dev.gf2log.app.capture.CaptureSnapshot
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.capture.format
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.history.SavedHistoryStore
import dev.gf2log.app.management.PlatoonCsvImportStore
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.AccessibleRow
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.divider
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.dpf
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.Gfl2PayloadDecoder
import java.io.File

/**
 * Home: capture state and actions first, then the selected Platoon, data tools, and recent
 * parsed-packet evidence. Capture state is observed through [CaptureStatus] listeners.
 */
class MainActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var screen: Screen
    private val work = BackgroundWork(this, "GF2Home")
    private lateinit var csvImport: CsvImportFlow
    private var historyStore: CaptureHistoryStore? = null
    private var savedStore: SavedHistoryStore? = null

    private lateinit var stateDot: View
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var captureActions: LinearLayout
    private lateinit var progressBanner: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var historySection: LinearLayout

    private var captureOnceRequested = false
    private var showingSaved = false
    private var selecting = false
    private val selectedIds = linkedSetOf<String>()
    private var showAll = false
    private var recent = emptyList<CaptureHistoryStore.Entry>()
    private var saved = emptyList<CaptureHistoryStore.Entry>()
    private var lastPhase: CapturePhase? = null

    private val statusListener: (CaptureSnapshot) -> Unit = ::renderCapture

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OnboardingPreferences.isCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        profileBinding = ActivePlatoonScopeBinding(this)
        profileBinding.scope?.rootDirectory(this)?.let { root ->
            historyStore = CaptureHistoryStore(File(root, CaptureHistoryStore.HISTORY_DIRECTORY))
            savedStore = SavedHistoryStore(File(root, SavedHistoryStore.SAVED_HISTORY_DIRECTORY))
        }
        csvImport = CsvImportFlow(this, work, { profileBinding.scope }, ::showProgress)
        csvImport.restoreState(savedInstanceState)
        savedInstanceState?.let {
            captureOnceRequested = it.getBoolean(STATE_CAPTURE_ONCE)
            showingSaved = it.getBoolean(STATE_SHOWING_SAVED)
            showAll = it.getBoolean(STATE_SHOW_ALL)
        }
        onBackPressedDispatcher.addCallback(this, exitSelection)
        screen = Screen(this, PrimaryNavigation.Destination.HOME)
        screen.topBar
            .setLeading(ImageView(this).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(36))
            })
            .setTitle(getString(R.string.app_name))
            .setActions(listOf(BarAction(R.drawable.ic_settings, getString(R.string.open_options)) {
                startActivity(Intent(this, OptionsActivity::class.java))
            }))
        screen.setBody(Screen.scrollingBody(this, buildContent()))
    }

    override fun onStart() {
        super.onStart()
        if (::stateTitle.isInitialized) CaptureStatus.addListener(statusListener)
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
        loadHistory()
        csvImport.onScreenReady()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::csvImport.isInitialized) csvImport.saveState(outState)
        outState.putBoolean(STATE_CAPTURE_ONCE, captureOnceRequested)
        outState.putBoolean(STATE_SHOWING_SAVED, showingSaved)
        outState.putBoolean(STATE_SHOW_ALL, showAll)
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
        addView(capturePanel(), lp(top = 4))
        addView(sectionHeader(getString(R.string.active_platoon_section)), lp())
        addView(PlatoonProfileSelector.card(this@MainActivity), lp())
        addView(sectionHeader(getString(R.string.data_tools)), lp())
        addView(group(listOf(
            navigationRow(getString(R.string.import_platoon_csv), getString(R.string.import_platoon_csv_detail), R.drawable.ic_upload) {
                csvImport.start()
            },
            navigationRow(getString(R.string.undo_last_csv_import), getString(R.string.undo_last_csv_import_detail), R.drawable.ic_undo) {
                csvImport.undo()
            },
            navigationRow(
                getString(R.string.export_latest_platoon_csv),
                getString(R.string.export_latest_platoon_csv_detail),
                R.drawable.ic_download,
            ) { exportLatestRoster() },
            navigationRow(getString(R.string.backup_and_restore), getString(R.string.backup_and_restore_detail), R.drawable.ic_storage) {
                startActivity(Intent(this@MainActivity, OptionsActivity::class.java))
            },
        )), lp())
        historySection = column()
        addView(historySection, lp())
    }

    // region Capture

    private fun capturePanel(): View {
        stateDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stateTitle = text(null, TextRole.HEADLINE) { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        stateDetail = text(null, TextRole.SECONDARY) { maxLines = 4 }
        checklist = column { visibility = View.GONE }
        captureActions = row(Gravity.TOP)
        return panel(outlined = true) {
            addView(row {
                addView(stateDot, lp(10, 10, end = 8))
                addView(text(getString(R.string.capture_status_label), TextRole.LABEL), lp(width = 0, weight = 1f))
            }, lp())
            addView(stateTitle, lp(top = 6))
            addView(stateDetail, lp(top = 2))
            addView(checklist, lp(top = 8))
            addView(clientRows(), lp(top = 12))
            addView(captureActions, lp(top = 12))
        }
    }

    private fun clientRows(): View = column {
        SupportedGamePackages.all.forEachIndexed { index, packageName ->
            val installed = isInstalled(packageName)
            val client = clientName(packageName)
            val status = getString(if (installed) R.string.client_installed else R.string.client_not_installed)
            addView(row {
                minimumHeight = dp(40)
                isFocusable = true
                contentDescription = "$client. $packageName. $status"
                addView(column {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(text(client, TextRole.BODY), lp())
                    addView(text(packageName, TextRole.CAPTION) {
                        typeface = Typeface.MONOSPACE
                        textSize = 11f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }, lp())
                }, lp(width = 0, weight = 1f))
                addView(badge(
                    status,
                    if (installed) R.color.success_surface else R.color.surface_variant,
                    if (installed) R.color.success_text else R.color.text_secondary,
                ).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(WRAP, WRAP, start = 8))
            }, lp(top = if (index == 0) 0 else 6))
        }
    }

    private fun renderCapture(snapshot: CaptureSnapshot) {
        val phase = snapshot.phase
        val (title, color) = when (phase) {
            CapturePhase.STOPPED -> R.string.capture_ready to R.color.text_primary
            CapturePhase.STARTING -> R.string.status_preparing to R.color.accent_text
            CapturePhase.RUNNING -> R.string.capture_running to R.color.success_text
        }
        stateTitle.text = getString(title)
        stateTitle.setTextColor(getColor(color))
        (stateDot.background as GradientDrawable).setColor(
            getColor(
                when (phase) {
                    CapturePhase.STOPPED -> R.color.outline_strong
                    CapturePhase.STARTING -> R.color.accent
                    CapturePhase.RUNNING -> R.color.success_mark
                },
            ),
        )
        val notice = snapshot.notice
        stateDetail.text = when {
            notice?.notice == CaptureNotice.CAPTURING -> getString(R.string.capturing_clients, clientNames(snapshot.capturedTargets))
            notice != null && notice.notice != CaptureNotice.PREPARING -> notice.format(this)
            phase == CapturePhase.STOPPED -> getString(R.string.capture_ready_detail)
            else -> getString(R.string.capture_preparing_detail)
        }
        stateDetail.setTextColor(
            getColor(
                when (notice?.notice?.severity) {
                    CaptureNotice.Severity.ERROR -> R.color.destructive_action
                    CaptureNotice.Severity.WARNING -> R.color.warning_text
                    else -> R.color.text_secondary
                },
            ),
        )
        renderChecklist(snapshot.guided)
        if (phase != lastPhase) {
            lastPhase = phase
            renderCaptureActions(phase)
        }
    }

    private fun clientNames(packages: List<String>): String {
        val names = packages.map(::clientName)
        return when (names.size) {
            0 -> getString(R.string.none)
            1 -> names.single()
            else -> getString(R.string.clients_and, names[0], names.drop(1).joinToString(", "))
        }
    }

    private fun clientName(packageName: String) =
        if (packageName == SupportedGamePackages.HAOPLAY) "HaoPlay" else if (packageName == SupportedGamePackages.DARKWINTER) "Darkwinter" else packageName

    private fun renderChecklist(guided: CaptureStatus.GuidedProgress?) {
        checklist.removeAllViews()
        checklist.visibility = if (guided == null) View.GONE else View.VISIBLE
        guided ?: return
        listOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE to R.string.checklist_identity,
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS to R.string.checklist_roster,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY to R.string.checklist_activity,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES to R.string.checklist_updates,
        ).forEach { (type, label) ->
            val done = type in guided.capturedPayloadTypes
            checklist.addView(row {
                minimumHeight = dp(32)
                isFocusable = true
                contentDescription = getString(label) + ". " + getString(if (done) R.string.captured else R.string.waiting)
                addView(icon(
                    if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline,
                    if (done) R.color.success_mark else R.color.outline_strong,
                    sizeDp = 20,
                ), lp(20, 20, end = 10))
                addView(text(getString(label), TextRole.BODY) {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    if (!done) setTextColor(getColor(R.color.text_secondary))
                }, lp(width = 0, weight = 1f))
            }, lp())
        }
    }

    private fun renderCaptureActions(phase: CapturePhase) {
        captureActions.removeAllViews()
        if (phase == CapturePhase.STOPPED) {
            captureActions.addView(
                button(getString(R.string.capture_one_roster), Controls.Role.PRIMARY, R.drawable.ic_bolt) {
                    beginCapture(once = true)
                },
                lp(width = 0, weight = 1f, end = 4),
            )
            captureActions.addView(
                button(getString(R.string.prepare_capture), Controls.Role.CAPTURE, R.drawable.ic_play) {
                    beginCapture(once = false)
                },
                lp(width = 0, weight = 1f, start = 4),
            )
        } else {
            captureActions.addView(
                button(getString(R.string.stop_capture), Controls.Role.DESTRUCTIVE, R.drawable.ic_stop) {
                    stopCaptureService()
                },
                lp(),
            )
        }
    }

    private fun beginCapture(once: Boolean) {
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
    }

    private fun stopCaptureService() {
        startService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
        CaptureStatus.markStopped()
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    // endregion

    // region Data tools

    private fun showProgress(message: CharSequence?) {
        progressBanner.visibility = if (message == null) View.GONE else View.VISIBLE
        progressText.text = message
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

    // region Packet history

    private fun loadHistory() {
        val recentStore = historyStore
        val savedEntries = savedStore
        if (recentStore == null || savedEntries == null) {
            renderHistory()
            return
        }
        work.run(key = HISTORY, task = { recentStore.list() to savedEntries.list() }) { result ->
            result.getOrNull()?.let { (recentEntries, savedList) ->
                recent = recentEntries
                saved = savedList
                val ids = (if (showingSaved) saved else recent).mapTo(mutableSetOf()) { it.id }
                selectedIds.retainAll(ids)
            }
            renderHistory()
        }
    }

    private fun renderHistory() {
        historySection.removeAllViews()
        val entries = if (showingSaved) saved else recent
        historySection.addView(row {
            addView(sectionHeader(getString(R.string.packet_history_section)), lp(width = 0, weight = 1f))
            if (entries.isNotEmpty()) {
                addView(
                    button(getString(if (selecting) R.string.done else R.string.select), Controls.Role.TERTIARY) {
                        setSelecting(!selecting)
                    },
                    lp(WRAP, WRAP, top = 8),
                )
            }
        }, lp())
        historySection.addView(Controls.segmentedControl(
            context = this,
            options = listOf(
                RECENT to getString(R.string.recent_packets_tab, recent.size, CaptureHistoryStore.MAX_ENTRIES),
                SAVED to getString(R.string.saved_packets_tab, saved.size, SavedHistoryStore.MAX_ENTRIES),
            ),
            selectedValue = if (showingSaved) SAVED else RECENT,
        ) { value ->
            showingSaved = value == SAVED
            showAll = false
            setSelecting(false)
        }, lp())
        if (entries.isEmpty()) {
            historySection.addView(text(
                getString(
                    when {
                        profileBinding.scope == null -> R.string.no_platoon_history
                        showingSaved -> R.string.no_saved_packets
                        else -> R.string.no_parsed_packets
                    },
                ),
                TextRole.SECONDARY,
            ) { setPadding(dp(4), dp(16), dp(4), dp(8)) }, lp())
            return
        }
        if (selecting) historySection.addView(selectionActions(), lp(top = 8))
        val visible = if (showAll) entries else entries.take(COLLAPSED_COUNT)
        historySection.addView(panel(padding = 4) {
            visible.forEachIndexed { index, entry ->
                if (index > 0) addView(divider(insetStartDp = 12))
                addView(historyRow(entry), lp())
            }
        }, lp(top = 8))
        if (!showAll && entries.size > COLLAPSED_COUNT) {
            historySection.addView(button(getString(R.string.show_all_count, entries.size), Controls.Role.TERTIARY) {
                showAll = true
                renderHistory()
            }, lp(top = 4))
        }
    }

    private fun historyRow(entry: CaptureHistoryStore.Entry): View {
        val isSelected = entry.id in selectedIds
        val tag = Labels.payloadTag(this, entry.payloadType)
        return AccessibleRow(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(12), dp(4), dp(8), dp(4))
            background = selectableRect()
            this.isSelected = selecting && isSelected
            contentDescription = listOfNotNull(
                getString(R.string.selected).takeIf { selecting && isSelected },
                entry.title,
                getString(
                    R.string.payload_tag_description,
                    tag,
                    entry.payloadType?.toString() ?: getString(R.string.unknown_payload_type),
                ),
            ).joinToString(". ")
            if (selecting) {
                addView(icon(
                    if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline,
                    if (isSelected) R.color.accent else R.color.outline_strong,
                    sizeDp = 22,
                ), lp(22, 22, end = 12))
            }
            addView(text(entry.title, TextRole.BODY) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, lp(width = 0, weight = 1f))
            addView(text(tag, TextRole.LABEL) {
                textSize = 11f
                setTextColor(getColor(R.color.accent_text))
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.accent_surface))
                    cornerRadius = dpf(8f)
                }
            }, lp(WRAP, WRAP, start = 8))
            if (!selecting) addView(icon(R.drawable.ic_chevron_right, sizeDp = 20), lp(20, 20, start = 4))
            setOnClickListener {
                if (selecting) {
                    if (!selectedIds.add(entry.id)) selectedIds.remove(entry.id)
                    renderHistory()
                } else {
                    openEntry(entry)
                }
            }
            setOnLongClickListener {
                if (!selecting) setSelecting(true)
                selectedIds.add(entry.id)
                renderHistory()
                true
            }
        }
    }

    private fun selectionActions(): View = row {
        addView(
            text(resources.getQuantityString(R.plurals.selected_count, selectedIds.size, selectedIds.size), TextRole.LABEL),
            lp(width = 0, weight = 1f, start = 4),
        )
        if (!showingSaved) {
            addView(button(getString(R.string.save_selected_history_short), Controls.Role.SECONDARY, R.drawable.ic_save) {
                saveSelected()
            }.apply { isEnabled = selectedIds.isNotEmpty() }, lp(WRAP, WRAP, end = 8))
        }
        addView(button(getString(R.string.delete), Controls.Role.DESTRUCTIVE_TEXT, R.drawable.ic_delete) {
            confirmDeleteSelected()
        }.apply { isEnabled = selectedIds.isNotEmpty() }, lp(WRAP, WRAP))
    }

    private fun setSelecting(enabled: Boolean) {
        selecting = enabled
        if (!enabled) selectedIds.clear()
        exitSelection.isEnabled = enabled
        renderHistory()
    }

    private fun openEntry(entry: CaptureHistoryStore.Entry) {
        startActivity(
            Intent(this, PacketHistoryActivity::class.java)
                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_ID, entry.id)
                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_TITLE, entry.title)
                .putExtra(PacketHistoryActivity.EXTRA_SAVED_ENTRY, showingSaved)
                .putExtra(PacketHistoryActivity.EXTRA_STORAGE_ID, profileBinding.scope?.storageId),
        )
    }

    private fun saveSelected() {
        val source = historyStore ?: return
        val target = savedStore ?: return
        val ids = selectedIds.toList()
        work.run(task = { target.saveFrom(source, ids) }) { result ->
            val saveResult = result.getOrNull()
            Feedback.show(
                this,
                when {
                    saveResult == null -> getString(R.string.status_save_history_failed)
                    saveResult.saved > 0 && saveResult.limitReached -> resources.getQuantityString(
                        R.plurals.status_saved_history_at_limit,
                        saveResult.saved,
                        saveResult.saved,
                        SavedHistoryStore.MAX_ENTRIES,
                    )
                    saveResult.saved > 0 ->
                        resources.getQuantityString(R.plurals.status_saved_history, saveResult.saved, saveResult.saved)
                    saveResult.limitReached -> getString(R.string.status_saved_history_limit, SavedHistoryStore.MAX_ENTRIES)
                    else -> getString(R.string.status_history_already_saved)
                },
            )
            setSelecting(false)
            loadHistory()
        }
    }

    private fun confirmDeleteSelected() {
        val ids = selectedIds.toList()
        val fromSaved = showingSaved
        Dialogs.confirm(
            this,
            getString(R.string.delete_history_title),
            resources.getQuantityString(R.plurals.delete_history_message, ids.size, ids.size),
            getString(R.string.delete),
            destructive = true,
        ) {
            work.run(task = { (if (fromSaved) savedStore?.delete(ids) else historyStore?.delete(ids)) ?: 0 }) { result ->
                val deleted = result.getOrDefault(0)
                Feedback.show(
                    this,
                    resources.getQuantityString(
                        if (fromSaved) R.plurals.status_deleted_saved_history else R.plurals.status_deleted_history,
                        deleted,
                        deleted,
                    ),
                )
                setSelecting(false)
                loadHistory()
            }
        }
    }

    // endregion

    private companion object {
        const val HISTORY = "history"
        const val RECENT = "recent"
        const val SAVED = "saved"
        const val COLLAPSED_COUNT = 20
        const val STATE_CAPTURE_ONCE = "home.capture_once"
        const val STATE_SHOWING_SAVED = "home.showing_saved"
        const val STATE_SHOW_ALL = "home.show_all"
    }
}
