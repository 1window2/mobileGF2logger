package dev.gf2log.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.history.SavedHistoryStore
import dev.gf2log.app.management.PlatoonBackupManager
import dev.gf2log.app.management.CsvImportCheckpointManager
import dev.gf2log.app.management.CsvImportPreviewAnalyzer
import dev.gf2log.app.management.PlatoonCsvImportStore
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.management.BackupFileName
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.PayloadCatalog
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : LocalizedActivity() {
    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var statusText: TextView
    private lateinit var captureStateText: TextView
    private lateinit var captureStatusText: TextView
    private lateinit var prepareCaptureButton: Button
    private lateinit var captureOnceButton: Button
    private lateinit var stopCaptureButton: Button
    private lateinit var guidedCaptureText: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyActions: LinearLayout
    private lateinit var savedHistoryContainer: LinearLayout
    private lateinit var savedHistoryActions: LinearLayout
    private lateinit var historyStore: CaptureHistoryStore
    private lateinit var savedHistoryStore: SavedHistoryStore
    private val selectedHistoryIds = linkedSetOf<String>()
    private val selectedSavedHistoryIds = linkedSetOf<String>()
    private val statusHandler = Handler(Looper.getMainLooper())
    private val fileIoExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2FileIo")
    }
    private val refreshStatus = object : Runnable {
        override fun run() {
            renderCaptureStatus()
            renderGuidedCaptureProgress()
            statusHandler.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }
    private var pendingExport: File? = null
    private var captureOnceRequested = false
    private var pendingCsvImport: PendingCsvImport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        if (!OnboardingPreferences.isCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        historyStore = CaptureHistoryStore(
            File(filesDir, CaptureHistoryStore.HISTORY_DIRECTORY),
        )
        savedHistoryStore = SavedHistoryStore(
            File(filesDir, SavedHistoryStore.SAVED_HISTORY_DIRECTORY),
        )
        setContentView(buildContentView())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (!profileBinding.isCurrent(this)) {
            recreate()
            return
        }
        if (!::captureStatusText.isInitialized) return
        renderCaptureStatus()
        refreshHistory()
        statusHandler.postDelayed(refreshStatus, STATUS_REFRESH_MILLIS)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    override fun onDestroy() {
        fileIoExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Uses the platform VPN consent activity without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            requestCode == REQUEST_VPN && resultCode == RESULT_OK -> startCaptureService()
            requestCode == REQUEST_EXPORT -> {
                val source = pendingExport
                pendingExport = null
                val destination = data?.data
                if (resultCode != RESULT_OK || destination == null || source == null) return
                runFileOperation(
                    successMessage = { getString(R.string.status_exported, source.name) },
                    failureMessage = { getString(R.string.status_export_failed) },
                ) {
                    val output = TrustedExportDestination.openOutputStream(
                        contentResolver,
                        destination,
                    )
                        ?: error("Document provider did not open an output stream")
                    output.writer(Charsets.UTF_8).use { writer ->
                        val snapshot = requireNotNull(
                            GuildMembersCsv.parse(source.readText(Charsets.UTF_8)),
                        ) { "Stored Platoon CSV is invalid" }
                        writer.write(GuildMembersCsv.formatForSpreadsheet(snapshot))
                    }
                }
            }
            requestCode == REQUEST_BACKUP_EXPORT -> {
                val destination = data?.data
                if (resultCode != RESULT_OK || destination == null) return
                runFileOperation(
                    successMessage = { getString(R.string.status_backup_exported) },
                    failureMessage = { getString(R.string.status_backup_failed) },
                ) {
                    val output = TrustedExportDestination.openOutputStream(
                        contentResolver,
                        destination,
                    ) ?: error("Document provider did not open an output stream")
                    output.use { PlatoonBackupManager(this).export(it) }
                }
            }
            requestCode == REQUEST_BACKUP_IMPORT -> {
                val source = data?.data
                if (resultCode != RESULT_OK || source == null) return
                if (!hasBackupExtension(source)) {
                    statusText.text = getString(R.string.invalid_platoon_backup)
                    return
                }
                runFileOperation(
                    successMessage = { getString(R.string.status_backup_restored) },
                    failureMessage = { getString(R.string.status_backup_failed) },
                ) {
                    val input = TrustedImportSource.openInputStream(contentResolver, source)
                        ?: error("Document provider did not open an input stream")
                    input.use { PlatoonBackupManager(this).restore(it) }
                }
            }
            requestCode == REQUEST_CSV_IMPORT -> {
                if (resultCode != RESULT_OK) return
                val sources = buildList {
                    data?.clipData?.let { clip ->
                        repeat(clip.itemCount) { index -> add(clip.getItemAt(index).uri) }
                    }
                    data?.data?.let(::add)
                }.distinct()
                if (sources.isNotEmpty()) preparePlatoonCsvSources(sources)
            }
        }
    }

    private fun runFileOperation(
        successMessage: () -> String,
        failureMessage: () -> String,
        operation: () -> Unit,
    ) {
        fileIoExecutor.execute {
            val succeeded = runCatching(operation).isSuccess
            statusHandler.post {
                if (!isFinishing && !isDestroyed) {
                    statusText.text = if (succeeded) successMessage() else failureMessage()
                }
            }
        }
    }

    private fun buildContentView(): View {
        val spacing = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, dp(10), spacing, dp(20))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(android.widget.ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(10)
                })
                addView(TextView(context).apply {
                    text = getString(R.string.app_name)
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_settings)
                    contentDescription = getString(R.string.open_options)
                    useModernIconStyle()
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, OptionsActivity::class.java))
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }, matchWidth())

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ModernUi.panelBackground(context).apply {
                    setStroke(dp(1), getColor(R.color.outline))
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(getColor(R.color.accent))
                        }
                    }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
                    addView(TextView(context).apply {
                        text = getString(R.string.capture_status_label)
                        textSize = 13f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(
                        PlatoonProfileSelector.controls(this@MainActivity, compact = true),
                        LinearLayout.LayoutParams(
                            dp(200),
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }, matchWidth())
                captureStateText = TextView(context).apply {
                    textSize = 22f
                    setTextColor(getColor(R.color.success_text))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), 0, 0)
                }
                addView(captureStateText, matchWidth())
                captureStatusText = TextView(context).apply {
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(1), 0, dp(8))
                }
                addView(captureStatusText, matchWidth())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = getString(R.string.capture_target)
                        textSize = 12f
                        setTextColor(getColor(R.color.text_secondary))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ImageButton(context).apply {
                        setImageResource(R.drawable.ic_info_outline)
                        contentDescription = getString(R.string.target_package_info)
                        useModernIconStyle()
                        setOnClickListener(::showTargetPackageInfo)
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }, matchWidth())
                addView(fixedTargetField(SupportedGamePackages.HAOPLAY), matchWidth())
                addView(
                    fixedTargetField(SupportedGamePackages.DARKWINTER),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(6) },
                )
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    prepareCaptureButton = Button(context).apply {
                        text = getString(R.string.prepare_capture)
                        useCaptureActionStyle()
                        allowCompactMultilineLabel()
                        setOnClickListener { requestVpnAndStart(captureOnce = false) }
                    }
                    addView(prepareCaptureButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginEnd = dp(3)
                    })
                    captureOnceButton = Button(context).apply {
                        text = getString(R.string.capture_one_roster)
                        usePrimaryActionStyle()
                        allowCompactMultilineLabel()
                        setOnClickListener { requestVpnAndStart(captureOnce = true) }
                    }
                    addView(captureOnceButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    })
                    stopCaptureButton = Button(context).apply {
                        text = getString(R.string.stop_capture)
                        useDestructiveActionStyle()
                        setOnClickListener { stopCaptureService() }
                    }
                    addView(stopCaptureButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginStart = dp(3)
                    })
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) })
                guidedCaptureText = TextView(context).apply {
                    visibility = View.GONE
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(4), 0, 0)
                }
                addView(guidedCaptureText, matchWidth())
                renderCaptureStatus()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })

            statusText = TextView(context).apply {
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(4), dp(6), dp(4), 0)
            }
            addView(statusText, matchWidth())

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(featureShortcut(
                    title = getString(R.string.platoon_management),
                    detail = getString(R.string.platoon_shortcut_detail),
                    icon = R.drawable.ic_group,
                ) { startActivity(Intent(this@MainActivity, PlatoonActivity::class.java)) },
                    LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(5) })
                addView(featureShortcut(
                    title = getString(R.string.weekly_table),
                    detail = getString(R.string.weekly_shortcut_detail),
                    icon = R.drawable.ic_calendar,
                ) { startActivity(Intent(this@MainActivity, WeeklyReportActivity::class.java)) },
                    LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginStart = dp(5) })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })

            addView(sectionLabel(getString(R.string.data_tools)), matchWidth())
            listOf(
                ModernUi.listRow(context, getString(R.string.import_platoon_csv), icon = R.drawable.ic_edit) {
                    selectPlatoonCsvFiles()
                },
                ModernUi.listRow(context, getString(R.string.export_platoon_backup), icon = R.drawable.ic_save) {
                    exportPlatoonBackup()
                },
                ModernUi.listRow(context, getString(R.string.undo_last_csv_import), icon = R.drawable.ic_arrow_back) {
                    confirmUndoLastCsvImport()
                },
                ModernUi.listRow(context, getString(R.string.import_platoon_backup), icon = R.drawable.ic_save) {
                    confirmImportPlatoonBackup()
                },
            ).forEach { row ->
                addView(row, matchWidth())
            }

            addView(sectionLabel(getString(R.string.recent_packets, CaptureHistoryStore.MAX_ENTRIES)), matchWidth())
            historyContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(historyContainer, matchWidth())
            historyActions = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Button(context).apply {
                    text = getString(R.string.delete_selected_history)
                    useDestructiveTextActionStyle()
                    setOnClickListener { deleteSelectedHistory() }
                }, matchWidth())
                addView(Button(context).apply {
                    text = getString(R.string.save_selected_history)
                    useSecondaryActionStyle()
                    setOnClickListener { saveSelectedHistory() }
                }, matchWidth())
            }
            addView(historyActions, matchWidth())

            addView(sectionLabel(getString(R.string.saved_packets, SavedHistoryStore.MAX_ENTRIES)), matchWidth())
            savedHistoryContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(savedHistoryContainer, matchWidth())
            savedHistoryActions = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Button(context).apply {
                    text = getString(R.string.delete_selected_saved_history)
                    useDestructiveTextActionStyle()
                    setOnClickListener { deleteSelectedSavedHistory() }
                }, matchWidth())
                addView(Button(context).apply {
                    text = getString(R.string.export_latest_platoon_csv)
                    useSecondaryActionStyle()
                    setOnClickListener { exportLatestPlatoonCsv() }
                }, matchWidth())
            }
            addView(savedHistoryActions, matchWidth())
        }
        val scroll = ScrollView(this).apply { addView(container, matchWidth()) }
        return PrimaryNavigation.wrap(this, scroll, PrimaryNavigation.Destination.HOME)
    }

    private fun sectionLabel(textValue: CharSequence, topPadding: Int = dp(18)) = TextView(this).apply {
        text = textValue
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), topPadding, 0, dp(7))
    }

    private fun featureShortcut(
        title: CharSequence,
        detail: CharSequence,
        icon: Int,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = "$title\n$detail"
        contentDescription = "$title. $detail"
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = ColorStateList.valueOf(getColor(R.color.accent_text))
        useFeatureActionStyle()
        setOnClickListener { onClick() }
    }

    private fun renderCaptureStatus() {
        if (!::captureStateText.isInitialized || !::captureStatusText.isInitialized) return
        val starting = CaptureStatus.isStarting
        val running = CaptureStatus.isRunning
        captureStateText.text = getString(
            when {
                starting -> R.string.status_preparing
                running -> R.string.capture_running
                else -> R.string.capture_ready
            },
        )
        val status = CaptureStatus.read()
        captureStatusText.text = when (status) {
            "Capture is stopped" -> getString(R.string.capture_stopped_detail)
            "Preparing capture" -> getString(R.string.status_preparing)
            else -> status
        }
        captureStateText.contentDescription = captureStateText.text
        captureStatusText.contentDescription = captureStatusText.text
        val busy = starting || running
        if (::prepareCaptureButton.isInitialized) prepareCaptureButton.isEnabled = !busy
        if (::captureOnceButton.isInitialized) captureOnceButton.isEnabled = !busy
        if (::stopCaptureButton.isInitialized) stopCaptureButton.isEnabled = busy
    }

    // Function Name: renderGuidedCaptureProgress
    // Description:
    // - Renders the four required Platoon payloads as a live checklist.
    // - Keeps display logic independent from capture-service status messages.
    // Parameters:
    // - None.
    // Returns:
    // - Unit after updating the checklist visibility and text.
    private fun renderGuidedCaptureProgress() {
        val progress = CaptureStatus.readGuidedProgress()
        guidedCaptureText.visibility = if (progress == null) View.GONE else View.VISIBLE
        if (progress == null) return
        fun mark(payloadType: Int): String =
            if (payloadType in progress.capturedPayloadTypes) "\u2713" else "\u25cb"
        guidedCaptureText.text = getString(
            R.string.guided_capture_checklist,
            mark(Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE),
            mark(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS),
            mark(Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY),
            mark(Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES),
        )
    }

    private fun showTargetPackageInfo(anchor: View) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = ModernUi.panelBackground(context).apply {
                setStroke(dp(1), getColor(R.color.outline_strong))
            }
            addView(TextView(context).apply {
                text = getString(R.string.target_package_info_title)
                textSize = 16f
                setTextColor(getColor(R.color.text_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.target_package_info_message)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(8), 0, 0)
            }, matchWidth())
        }
        val popupWidth = minOf(dp(360), resources.displayMetrics.widthPixels - dp(32))
        PopupWindow(
            content,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(6).toFloat()
            showAsDropDown(anchor, anchor.width - popupWidth, dp(4))
        }
    }

    private fun fixedTargetField(packageId: String) = TextView(this).apply {
        text = packageId
        textSize = 12f
        setTextColor(getColor(R.color.text_secondary))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(38)
        isClickable = false
        isFocusable = false
        background = ModernUi.panelBackground(context).apply {
            setStroke(dp(1), getColor(R.color.outline))
        }
    }

    @Suppress("DEPRECATION")
    private fun requestVpnAndStart(captureOnce: Boolean) {
        captureOnceRequested = captureOnce
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startCaptureService()
        } else {
            startActivityForResult(permissionIntent, REQUEST_VPN)
        }
    }

    private fun startCaptureService() {
        CaptureStatus.markStarting()
        renderCaptureStatus()
        val intent = Intent(this, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_START)
            .putExtra(CaptureVpnService.EXTRA_CAPTURE_ONCE, captureOnceRequested)
        startForegroundService(intent)
        captureOnceRequested = false
    }

    private fun stopCaptureService() {
        val intent = Intent(this, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_STOP)
        startService(intent)
        CaptureStatus.markStopped()
        renderCaptureStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    @Suppress("DEPRECATION")
    private fun exportPlatoonBackup() {
        if (CaptureStatus.isRunning) {
            statusText.text = getString(R.string.stop_capture_before_backup)
            return
        }
        val title = "GF2logger-platoon-${BACKUP_TIME.format(LocalDateTime.now())}." +
            PlatoonBackupManager.FILE_EXTENSION
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PlatoonBackupManager.MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, title)
        startActivityForResult(intent, REQUEST_BACKUP_EXPORT)
    }

    private fun confirmImportPlatoonBackup() {
        if (CaptureStatus.isRunning) {
            statusText.text = getString(R.string.stop_capture_before_backup)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_platoon_backup)
            .setMessage(R.string.import_backup_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_platoon_backup) { _, _ -> importPlatoonBackup() }
            .show()
    }

    @Suppress("DEPRECATION")
    // Function Name: selectPlatoonCsvFiles
    // Description:
    // - Opens Android's trusted document picker for one or more roster CSV files.
    // Parameters:
    // - None.
    // Returns:
    // - Unit after dispatching the picker activity.
    private fun selectPlatoonCsvFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, REQUEST_CSV_IMPORT)
    }


    // Function Name: preparePlatoonCsvSources
    // Description:
    // - Reconciles crash-left retained evidence before classifying selected source identities.
    // - Validates a bounded selection without retaining any newly selected source.
    // - Computes a user-visible impact preview from the recovered repository state.
    // Parameters:
    // - sources: Distinct document-provider URIs returned by the picker.
    // Returns:
    // - Unit after scheduling validation and preview display.
    private fun preparePlatoonCsvSources(sources: List<Uri>) {
        if (CaptureStatus.isRunning) {
            statusText.text = getString(R.string.stop_capture_before_csv_import)
            return
        }
        val storageScope = PlatoonProfileRegistry(this).activeScope()
        statusText.text = getString(R.string.csv_import_preparing_preview)
        fileIoExecutor.execute {
            val result = runCatching {
                // Recover an interrupted prior import before reading the preview baseline.
                CsvImportCheckpointManager(this, storageScope)
                require(sources.size <= MAX_CSV_IMPORT_FILES) {
                    "Too many Platoon CSV files were selected"
                }
                val directory = storageScope.retainedCsvDirectory(this)
                val store = PlatoonCsvImportStore(directory)
                val selected = ArrayList<PlatoonCsvImportStore.PreparedImport>(sources.size)
                var selectedBytes = 0L
                sources.forEach { source ->
                    val input = TrustedImportSource.openInputStream(contentResolver, source)
                        ?: error("Document provider did not open an input stream")
                    val prepared = input.use(store::prepare)
                    selectedBytes += prepared.byteCount
                    require(selectedBytes <= MAX_CSV_IMPORT_BYTES) {
                        "Selected Platoon CSV files exceed the total import size limit"
                    }
                    selected += prepared
                }
                val unique = selected.distinctBy(PlatoonCsvImportStore.PreparedImport::fileName)
                val repository = PlatoonRepository(this, storageScope)
                repository.reconcileRetainedCsvFiles(directory)
                val duplicateNames = CsvImportPreviewAnalyzer.duplicateFileNames(
                    prepared = unique,
                    representedSourceFiles = repository.representedSnapshotSources(),
                )
                val analyzed = CsvImportPreviewAnalyzer.analyze(
                    prepared = unique,
                    duplicateFileNames = duplicateNames,
                    existingMembers = repository.listMemberStatuses(),
                    latestSnapshot = repository.listSnapshots(limit = 1).firstOrNull(),
                )
                val preview = analyzed.copy(
                    validatedFiles = selected.size,
                    duplicateFiles = analyzed.duplicateFiles + selected.size - unique.size,
                    totalBytes = selectedBytes,
                )
                PendingCsvImport(storageScope, unique, duplicateNames, preview)
            }
            statusHandler.post {
                if (isFinishing || isDestroyed) return@post
                result.fold(
                    onSuccess = {
                        pendingCsvImport = it
                        showCsvImportPreview(it)
                    },
                    onFailure = {
                        pendingCsvImport = null
                        statusText.text = getString(R.string.status_platoon_csv_import_failed)
                    },
                )
            }
        }
    }

    private fun showCsvImportPreview(pending: PendingCsvImport) {
        val preview = pending.preview
        statusText.text = ""
        val range = if (preview.firstCapture == null || preview.lastCapture == null) {
            getString(R.string.none)
        } else {
            getString(
                R.string.csv_import_capture_range,
                preview.firstCapture.toString(),
                preview.lastCapture.toString(),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.csv_import_preview_title)
            .setMessage(
                getString(
                    R.string.csv_import_preview_summary,
                    preview.validatedFiles,
                    preview.duplicateFiles,
                    preview.historicalFiles,
                    preview.uniqueMembers,
                    preview.newMembers,
                    preview.nameDifferences,
                    preview.potentialJoins,
                    preview.potentialWithdrawals,
                    preview.totalBytes / 1024,
                    range,
                ),
            )
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingCsvImport = null }
            .setPositiveButton(R.string.import_platoon_csv) { _, _ ->
                applyPreparedCsvImport(pending)
            }
            .setOnCancelListener { pendingCsvImport = null }
            .show()
    }

    private fun applyPreparedCsvImport(pending: PendingCsvImport) {
        if (pendingCsvImport !== pending || CaptureStatus.isRunning) {
            pendingCsvImport = null
            statusText.text = getString(R.string.stop_capture_before_csv_import)
            return
        }
        pendingCsvImport = null
        statusText.text = getString(R.string.csv_import_applying)
        fileIoExecutor.execute {
            val directory = pending.storageScope.retainedCsvDirectory(this)
            val store = PlatoonCsvImportStore(directory)
            val checkpoint = CsvImportCheckpointManager(this, pending.storageScope)
            var checkpointCreated = false
            val result = runCatching {
                val plannedNames = pending.prepared
                    .filterNot { it.fileName in pending.duplicateFileNames }
                    .mapTo(mutableSetOf()) { it.fileName }
                checkpoint.create(plannedNames)
                checkpointCreated = true
                var retained = 0
                var duplicates = 0
                pending.prepared.forEach { prepared ->
                    store.retain(prepared).also {
                        if (it.duplicate) duplicates += 1 else retained += 1
                    }
                }
                val imported = PlatoonRepository(this, pending.storageScope)
                    .reconcileRetainedCsvFiles(directory)
                checkpoint.seal()
                CsvImportSummary(retained, duplicates, imported)
            }.recoverCatching { failure ->
                if (checkpointCreated) {
                    runCatching(checkpoint::rollbackFailedImport).exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
            statusHandler.post {
                if (isFinishing || isDestroyed) return@post
                statusText.text = result.fold(
                    onSuccess = { summary ->
                        getString(
                            R.string.status_platoon_csv_imported_with_checkpoint,
                            summary.retained,
                            summary.imported.imported,
                            summary.imported.historical,
                            summary.duplicates + summary.imported.skipped,
                        )
                    },
                    onFailure = { getString(R.string.status_platoon_csv_import_failed) },
                )
            }
        }
    }

    private fun confirmUndoLastCsvImport() {
        if (CaptureStatus.isRunning) {
            statusText.text = getString(R.string.stop_capture_before_csv_import)
            return
        }
        if (!CsvImportCheckpointManager(this).canUndo()) {
            statusText.text = getString(R.string.no_csv_import_checkpoint)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.undo_last_csv_import)
            .setMessage(R.string.undo_last_csv_import_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.undo_last_csv_import) { _, _ -> undoLastCsvImport() }
            .show()
    }

    private fun undoLastCsvImport() {
        statusText.text = getString(R.string.csv_import_undoing)
        fileIoExecutor.execute {
            val succeeded = runCatching { CsvImportCheckpointManager(this).restore() }.isSuccess
            statusHandler.post {
                if (isFinishing || isDestroyed) return@post
                statusText.text = getString(
                    if (succeeded) R.string.csv_import_undone else R.string.csv_import_undo_failed,
                )
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun importPlatoonBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, BACKUP_MIME_TYPES)
        startActivityForResult(intent, REQUEST_BACKUP_IMPORT)
    }

    private fun hasBackupExtension(uri: Uri): Boolean {
        val name = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor: Cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        } ?: return false
        return BackupFileName.isValid(name)
    }

    @Suppress("DEPRECATION")
    private fun exportLatestPlatoonCsv() {
        val directory = PlatoonProfileRegistry(this).activeScope()
            .retainedCsvDirectory(this)
        val latest = PlatoonCsvImportStore.latestRetainedFile(directory)
        if (latest == null) {
            statusText.text = getString(R.string.status_no_platoon_csv)
            return
        }

        pendingExport = latest
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, latest.name)
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun refreshHistory() {
        val recentEntries = historyStore.list()
        renderHistoryEntries(
            container = historyContainer,
            entries = recentEntries,
            selectedIds = selectedHistoryIds,
            saved = false,
            emptyMessage = R.string.no_parsed_packets,
        )
        historyActions.visibility = if (recentEntries.isEmpty()) View.GONE else View.VISIBLE

        val savedEntries = savedHistoryStore.list()
        renderHistoryEntries(
            container = savedHistoryContainer,
            entries = savedEntries,
            selectedIds = selectedSavedHistoryIds,
            saved = true,
            emptyMessage = R.string.no_saved_packets,
        )
        savedHistoryActions.visibility = if (savedEntries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderHistoryEntries(
        container: LinearLayout,
        entries: List<CaptureHistoryStore.Entry>,
        selectedIds: MutableSet<String>,
        saved: Boolean,
        emptyMessage: Int,
    ) {
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(emptyMessage)
            }, matchWidth())
            return
        }
        val rowHeight = dp(48)
        val tagWidth = dp(112)
        val tagHeight = dp(32)
        entries.forEach { entry ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowHeight
                addView(CheckBox(context).apply {
                    isChecked = entry.id in selectedIds
                    contentDescription = getString(R.string.select_history_entry, entry.title)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedIds += entry.id else selectedIds -= entry.id
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                addView(Button(context).apply {
                    text = entry.title
                    isAllCaps = false
                    useEvidenceActionStyle()
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, PacketHistoryActivity::class.java)
                                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_ID, entry.id)
                                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_TITLE, entry.title)
                                .putExtra(PacketHistoryActivity.EXTRA_SAVED_ENTRY, saved),
                        )
                    }
                }, LinearLayout.LayoutParams(0, rowHeight, 1f))
                addView(TextView(context).apply {
                    val localizedTag = localizedPayloadTag(entry.payloadType)
                    text = localizedTag
                    textSize = 12f
                    setTextColor(getColor(R.color.primary_action_foreground))
                    gravity = Gravity.CENTER
                    setPadding(dp(8), 0, dp(8), 0)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(getColor(R.color.primary_action_background))
                    }
                    contentDescription = getString(
                        R.string.payload_tag_description,
                        localizedTag,
                        entry.payloadType?.toString() ?: getString(R.string.unknown_payload_type),
                    )
                }, LinearLayout.LayoutParams(
                    tagWidth,
                    tagHeight,
                ).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun localizedPayloadTag(payloadType: Int?): String = when (payloadType) {
        Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> getString(R.string.payload_tag_platoon)
        Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY -> getString(R.string.payload_tag_activity)
        Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES -> getString(R.string.payload_tag_updates)
        Gfl2PayloadDecoder.TYPE_WEAPONS -> getString(R.string.payload_tag_weapons)
        Gfl2PayloadDecoder.TYPE_ATTACHMENTS -> getString(R.string.payload_tag_attachments)
        Gfl2PayloadDecoder.TYPE_COMMON_KEYS -> getString(R.string.payload_tag_common_keys)
        Gfl2PayloadDecoder.TYPE_FORMATIONS -> getString(R.string.payload_tag_formations)
        else -> PayloadCatalog.tag(payloadType)
    }

    private fun deleteSelectedHistory() {
        if (selectedHistoryIds.isEmpty()) {
            statusText.text = getString(R.string.status_select_history_first)
            return
        }
        val deleted = historyStore.delete(selectedHistoryIds)
        selectedHistoryIds.clear()
        statusText.text = resources.getQuantityString(
            R.plurals.status_deleted_history,
            deleted,
            deleted,
        )
        refreshHistory()
    }

    private fun saveSelectedHistory() {
        if (selectedHistoryIds.isEmpty()) {
            statusText.text = getString(R.string.status_select_history_first)
            return
        }
        val result = runCatching {
            savedHistoryStore.saveFrom(historyStore, selectedHistoryIds)
        }.getOrElse {
            statusText.text = getString(R.string.status_save_history_failed)
            return
        }
        selectedHistoryIds.clear()
        statusText.text = when {
            result.saved > 0 && result.limitReached -> resources.getQuantityString(
                R.plurals.status_saved_history_at_limit,
                result.saved,
                result.saved,
                SavedHistoryStore.MAX_ENTRIES,
            )
            result.saved > 0 -> resources.getQuantityString(
                R.plurals.status_saved_history,
                result.saved,
                result.saved,
            )
            result.limitReached -> getString(
                R.string.status_saved_history_limit,
                SavedHistoryStore.MAX_ENTRIES,
            )
            else -> getString(R.string.status_history_already_saved)
        }
        refreshHistory()
    }

    private fun deleteSelectedSavedHistory() {
        if (selectedSavedHistoryIds.isEmpty()) {
            statusText.text = getString(R.string.status_select_saved_history_first)
            return
        }
        val deleted = savedHistoryStore.delete(selectedSavedHistoryIds)
        selectedSavedHistoryIds.clear()
        statusText.text = resources.getQuantityString(
            R.plurals.status_deleted_saved_history,
            deleted,
            deleted,
        )
        refreshHistory()
    }

    private fun matchWidth(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_VPN = 100
        const val REQUEST_NOTIFICATIONS = 101
        const val REQUEST_EXPORT = 102
        const val REQUEST_BACKUP_EXPORT = 103
        const val REQUEST_BACKUP_IMPORT = 104
        const val REQUEST_CSV_IMPORT = 105
        const val MAX_CSV_IMPORT_FILES = 64
        const val MAX_CSV_IMPORT_BYTES = 16L * 1024 * 1024
        const val STATUS_REFRESH_MILLIS = 1_000L
        val BACKUP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val BACKUP_MIME_TYPES = arrayOf(
            PlatoonBackupManager.MIME_TYPE,
            "application/zip",
            "application/octet-stream",
        )
    }

    private data class CsvImportSummary(
        val retained: Int,
        val duplicates: Int,
        val imported: PlatoonRepository.ImportResult,
    )

    private data class PendingCsvImport(
        val storageScope: PlatoonStorageScope,
        val prepared: List<PlatoonCsvImportStore.PreparedImport>,
        val duplicateFileNames: Set<String>,
        val preview: CsvImportPreviewAnalyzer.Preview,
    )
}
