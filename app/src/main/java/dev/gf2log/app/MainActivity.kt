package dev.gf2log.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.management.BackupFileName
import dev.gf2log.app.settings.GameServerRegion
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
    private var pendingCsvPickerStorageId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCsvPickerStorageId = savedInstanceState?.getString(STATE_CSV_TARGET)
        profileBinding = ActivePlatoonScopeBinding(this)
        if (!OnboardingPreferences.isCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        val historyRoot = profileBinding.scope?.rootDirectory(this)
            ?: File(cacheDir, "no-profile-history")
        historyStore = CaptureHistoryStore(File(historyRoot, CaptureHistoryStore.HISTORY_DIRECTORY))
        savedHistoryStore = SavedHistoryStore(File(historyRoot, SavedHistoryStore.SAVED_HISTORY_DIRECTORY))
        setContentView(buildContentView())
        requestNotificationPermissionIfNeeded()
        intent.getStringExtra(EXTRA_LAUNCH_CSV_PICKER)
            ?.takeIf { it == profileBinding.scope?.storageId }
            ?.let { storageId ->
                intent.removeExtra(EXTRA_LAUNCH_CSV_PICKER)
                pendingCsvPickerStorageId = storageId
                window.decorView.post(::selectPlatoonCsvFiles)
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCsvPickerStorageId?.let { outState.putString(STATE_CSV_TARGET, it) }
        super.onSaveInstanceState(outState)
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
                val scope = profileBinding.scope ?: return
                runFileOperation(
                    successMessage = { getString(R.string.status_backup_exported) },
                    failureMessage = { getString(R.string.status_backup_failed) },
                ) {
                    val output = TrustedExportDestination.openOutputStream(
                        contentResolver,
                        destination,
                    ) ?: error("Document provider did not open an output stream")
                    output.use { PlatoonBackupManager(this, scope).export(it) }
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
                    onSuccess = { recreate() },
                ) {
                    val input = TrustedImportSource.openInputStream(contentResolver, source)
                        ?: error("Document provider did not open an input stream")
                    input.use { PlatoonBackupManager.restoreSelected(this, it, complete = false) }
                }
            }
            requestCode == REQUEST_CSV_IMPORT -> {
                if (resultCode != RESULT_OK) {
                    pendingCsvPickerStorageId = null
                    return
                }
                val sources = buildList {
                    data?.clipData?.let { clip ->
                        repeat(clip.itemCount) { index -> add(clip.getItemAt(index).uri) }
                    }
                    data?.data?.let(::add)
                }.distinct()
                if (sources.isNotEmpty()) {
                    val target = resolveCsvImportTarget(pendingCsvPickerStorageId)
                    if (target == null) {
                        pendingCsvPickerStorageId = null
                        statusText.text = getString(R.string.csv_import_target_changed)
                    } else {
                        preparePlatoonCsvSources(sources, target)
                    }
                } else {
                    pendingCsvPickerStorageId = null
                }
            }
        }
    }

    private fun runFileOperation(
        successMessage: () -> String,
        failureMessage: () -> String,
        onSuccess: () -> Unit = {},
        operation: () -> Unit,
    ) {
        fileIoExecutor.execute {
            val succeeded = runCatching(operation).isSuccess
            statusHandler.post {
                if (!isFinishing && !isDestroyed) {
                    statusText.text = if (succeeded) successMessage() else failureMessage()
                    if (succeeded) onSuccess()
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
                    gravity = Gravity.TOP
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(View(context).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(getColor(R.color.accent))
                                }
                            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                                marginEnd = dp(8)
                            })
                            addView(TextView(context).apply {
                                text = getString(R.string.capture_status_label)
                                textSize = 13f
                                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            })
                        }, matchWidth())
                        captureStateText = TextView(context).apply {
                            textSize = 22f
                            setTextColor(getColor(R.color.success_text))
                            setTypeface(typeface, Typeface.BOLD)
                            setPadding(0, dp(4), 0, 0)
                        }
                        addView(captureStateText, matchWidth())
                        captureStatusText = TextView(context).apply {
                            textSize = 13f
                            setTextColor(getColor(R.color.text_secondary))
                        }
                        addView(captureStatusText, matchWidth())
                    }, LinearLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(8)
                    })
                    addView(
                        PlatoonProfileSelector.controls(
                            this@MainActivity,
                            compact = true,
                            showManageButton = false,
                        ),
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply { topMargin = dp(18) },
                    )
                }, matchWidth())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(TextView(context).apply {
                        text = getString(R.string.capture_target)
                        textSize = 12f
                        setTextColor(getColor(R.color.text_secondary))
                        setPadding(0, 0, 0, dp(4))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ImageButton(context).apply {
                        setImageResource(R.drawable.ic_info_outline)
                        contentDescription = getString(R.string.target_package_info)
                        useModernIconStyle()
                        setOnClickListener(::showTargetPackageInfo)
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48),
                ))
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

            addView(sectionLabel(getString(R.string.data_tools), topPadding = dp(12)), matchWidth())
            listOf(
                ModernUi.listRow(context, getString(R.string.import_platoon_csv), icon = R.drawable.ic_edit) {
                    showCsvImportSelector()
                },
                ModernUi.listRow(context, getString(R.string.export_platoon_backup), icon = R.drawable.ic_save) {
                    if (requireActiveScope() != null) exportPlatoonBackup()
                },
                ModernUi.listRow(context, getString(R.string.undo_last_csv_import), icon = R.drawable.ic_arrow_back) {
                    if (requireActiveScope() != null) confirmUndoLastCsvImport()
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
        val detail = when {
            status.startsWith("Capturing only ") -> ""
            status == "Capture is stopped" -> getString(R.string.capture_stopped_detail)
            status == "Preparing capture" -> getString(R.string.status_preparing)
            else -> status
        }
        captureStatusText.text = detail
        captureStatusText.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
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

    private fun requireActiveScope(): PlatoonStorageScope? = profileBinding.scope.also { scope ->
        if (scope == null) {
            showNoPlatoonMessage()
        }
    }

    private fun showNoPlatoonMessage() {
        TransientMessage.show(this, R.string.no_platoon_detected_detail, android.widget.Toast.LENGTH_LONG)
    }

    private fun showCsvImportSelector() {
        if (CaptureStatus.isRunning) {
            statusText.text = getString(R.string.stop_capture_before_csv_import)
            return
        }
        PlatoonCsvImportPrompt.show(this, ::beginCsvImport)
    }

    private fun beginCsvImport(profile: PlatoonProfile) {
        val registry = PlatoonProfileRegistry(this)
        if (!registry.setActive(profile.storageId)) {
            statusText.text = getString(R.string.csv_import_target_changed)
            return
        }
        pendingCsvPickerStorageId = profile.storageId
        if (profileBinding.scope?.storageId == profile.storageId) {
            selectPlatoonCsvFiles()
        } else {
            intent.putExtra(EXTRA_LAUNCH_CSV_PICKER, profile.storageId)
            recreate()
        }
    }

    private fun resolveCsvImportTarget(storageId: String?): CsvImportTarget? {
        val scope = storageId?.let(::PlatoonStorageScope) ?: return null
        val registry = PlatoonProfileRegistry(this)
        val activeProfile = registry.active()
        if (
            activeProfile == null ||
            activeProfile.storageId != scope.storageId ||
            activeProfile.legacy ||
            profileBinding.scope != scope
        ) {
            statusText.text = getString(R.string.csv_import_target_changed)
            return null
        }
        return CsvImportTarget(scope, activeProfile)
    }

    private fun openScopedActivity(activityClass: Class<out Activity>) {
        if (requireActiveScope() != null) startActivity(Intent(this, activityClass))
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
        if (resolveCsvImportTarget(pendingCsvPickerStorageId) == null) {
            pendingCsvPickerStorageId = null
            return
        }
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
    private fun preparePlatoonCsvSources(sources: List<Uri>, target: CsvImportTarget) {
        if (CaptureStatus.isRunning) {
            statusText.text = getString(R.string.stop_capture_before_csv_import)
            return
        }
        val storageScope = target.storageScope
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
                PendingCsvImport(
                    storageScope = storageScope,
                    destinationProfile = target.profile,
                    prepared = unique,
                    duplicateFileNames = duplicateNames,
                    preview = preview,
                )
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
                        pendingCsvPickerStorageId = null
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
                    R.string.csv_import_destination,
                    pending.destinationProfile.platoonName,
                    pending.destinationProfile.platoonId,
                    pending.destinationProfile.client.displayName,
                    csvImportRegionLabel(pending.destinationProfile.serverRegion),
                ) + "\n\n" + getString(
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
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingCsvImport = null
                pendingCsvPickerStorageId = null
            }
            .setPositiveButton(R.string.import_platoon_csv) { _, _ ->
                applyPreparedCsvImport(pending)
            }
            .setOnCancelListener {
                pendingCsvImport = null
                pendingCsvPickerStorageId = null
            }
            .show()
    }

    private fun csvImportRegionLabel(region: GameServerRegion): String = getString(
        when (region) {
            GameServerRegion.MANUAL -> R.string.server_region_manual
            GameServerRegion.DARKWINTER_GLOBAL -> R.string.server_region_darkwinter_global
            GameServerRegion.DARKWINTER_CHINA -> R.string.server_region_darkwinter_china
            GameServerRegion.HAOPLAY_GLOBAL -> R.string.server_region_haoplay_global
            GameServerRegion.HAOPLAY_JAPAN -> R.string.server_region_haoplay_japan
            GameServerRegion.HAOPLAY_KOREA -> R.string.server_region_haoplay_korea
            GameServerRegion.HAOPLAY_ASIA -> R.string.server_region_haoplay_asia
        },
    )

    private fun applyPreparedCsvImport(pending: PendingCsvImport) {
        val registry = PlatoonProfileRegistry(this)
        val targetStillExists = registry.find(pending.storageScope.storageId)
            ?.let {
                it.client == pending.destinationProfile.client &&
                    it.platoonId == pending.destinationProfile.platoonId
            }
            ?: false
        val targetStillSelected = registry.active()?.storageId == pending.storageScope.storageId
        if (
            pendingCsvImport !== pending ||
            CaptureStatus.isRunning ||
            profileBinding.scope != pending.storageScope ||
            !targetStillExists ||
            !targetStillSelected
        ) {
            pendingCsvImport = null
            pendingCsvPickerStorageId = null
            statusText.text = getString(
                if (CaptureStatus.isRunning) {
                    R.string.stop_capture_before_csv_import
                } else {
                    R.string.csv_import_target_changed
                },
            )
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
                pendingCsvPickerStorageId = null
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
        val scope = requireActiveScope() ?: return
        if (!CsvImportCheckpointManager(this, scope).canUndo()) {
            TransientMessage.show(this, R.string.no_csv_import_checkpoint)
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
        val scope = requireActiveScope() ?: return
        statusText.text = getString(R.string.csv_import_undoing)
        fileIoExecutor.execute {
            val succeeded = runCatching { CsvImportCheckpointManager(this, scope).restore() }.isSuccess
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
        val directory = (requireActiveScope() ?: return).retainedCsvDirectory(this)
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
                                .putExtra(PacketHistoryActivity.EXTRA_SAVED_ENTRY, saved)
                                .putExtra(
                                    PacketHistoryActivity.EXTRA_STORAGE_ID,
                                    profileBinding.scope?.storageId,
                                ),
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
        Gfl2PayloadDecoder.TYPE_WEAPON_MODS -> getString(R.string.payload_tag_attachments)
        Gfl2PayloadDecoder.TYPE_PUBLIC_SKILL_ITEMS -> getString(R.string.payload_tag_common_keys)
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
        const val EXTRA_LAUNCH_CSV_PICKER = "launch_csv_picker_for_storage_id"
        const val STATE_CSV_TARGET = "pending_csv_target"
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
        val destinationProfile: PlatoonProfile,
        val prepared: List<PlatoonCsvImportStore.PreparedImport>,
        val duplicateFileNames: Set<String>,
        val preview: CsvImportPreviewAnalyzer.Preview,
    )

    private data class CsvImportTarget(
        val storageScope: PlatoonStorageScope,
        val profile: PlatoonProfile,
    )
}
