package dev.gf2log.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.capture.GuildMembersCsvWriter
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.history.SavedHistoryStore
import dev.gf2log.protocol.PayloadCatalog
import java.io.File

class MainActivity : Activity() {
    private lateinit var packageNameInput: EditText
    private lateinit var statusText: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var savedHistoryContainer: LinearLayout
    private lateinit var historyStore: CaptureHistoryStore
    private lateinit var savedHistoryStore: SavedHistoryStore
    private val selectedHistoryIds = linkedSetOf<String>()
    private val selectedSavedHistoryIds = linkedSetOf<String>()
    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() {
            statusText.text = CaptureStatus.read()
            statusHandler.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }
    private var pendingExport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        statusText.text = CaptureStatus.read()
        refreshHistory()
        statusHandler.postDelayed(refreshStatus, STATUS_REFRESH_MILLIS)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatus)
        super.onPause()
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
                val destinationText = destination.toString()
                if (destinationText.contains("..")) return
                if (!destinationText.startsWith("content://")) return
                val exported = runCatching {
                    val output = contentResolver.openOutputStream(destination)
                        ?: error("Document provider did not open an output stream")
                    output.use { stream ->
                        source.inputStream().use { input -> input.copyTo(stream) }
                    }
                }.isSuccess
                statusText.text = if (exported) {
                    getString(R.string.status_exported, source.name)
                } else {
                    getString(R.string.status_export_failed)
                }
            }
        }
    }

    private fun buildContentView(): ScrollView {
        val spacing = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = getString(R.string.app_name)
                    textSize = 28f
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_settings)
                    contentDescription = getString(R.string.open_options)
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, OptionsActivity::class.java))
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.app_description)
                textSize = 16f
                setPadding(0, spacing / 2, 0, spacing)
            })

            packageNameInput = EditText(context).apply {
                hint = getString(R.string.target_package_hint)
                setSingleLine(true)
                setText(
                    getPreferences(MODE_PRIVATE)
                        .getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE),
                )
            }
            addView(packageNameInput, matchWidth())

            addView(Button(context).apply {
                text = getString(R.string.prepare_capture)
                setOnClickListener { requestVpnAndStart() }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.stop_capture)
                setOnClickListener { stopCaptureService() }
            }, matchWidth())

            statusText = TextView(context).apply {
                text = CaptureStatus.read()
                textSize = 15f
                setPadding(0, spacing, 0, 0)
            }
            addView(statusText, matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.recent_packets, CaptureHistoryStore.MAX_ENTRIES)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, spacing / 2)
            }, matchWidth())
            historyContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(historyContainer, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.delete_selected_history)
                setOnClickListener { deleteSelectedHistory() }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.save_selected_history)
                setOnClickListener { saveSelectedHistory() }
            }, matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.saved_packets, SavedHistoryStore.MAX_ENTRIES)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, spacing / 2)
            }, matchWidth())
            savedHistoryContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(savedHistoryContainer, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.delete_selected_saved_history)
                setOnClickListener { deleteSelectedSavedHistory() }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.export_latest_platoon_csv)
                setOnClickListener { exportLatestPlatoonCsv() }
            }, matchWidth())
        }
        return ScrollView(this).apply { addView(container, matchWidth()) }
    }

    @Suppress("DEPRECATION")
    private fun requestVpnAndStart() {
        val targetPackage = packageNameInput.text.toString().trim()
        getPreferences(MODE_PRIVATE).edit().putString(KEY_TARGET_PACKAGE, targetPackage).apply()
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startCaptureService()
        } else {
            startActivityForResult(permissionIntent, REQUEST_VPN)
        }
    }

    private fun startCaptureService() {
        val intent = Intent(this, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_START)
            .putExtra(CaptureVpnService.EXTRA_TARGET_PACKAGE, packageNameInput.text.toString().trim())
        startForegroundService(intent)
        statusText.text = getString(R.string.status_preparing)
    }

    private fun stopCaptureService() {
        val intent = Intent(this, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_STOP)
        startService(intent)
        CaptureStatus.markStopped()
        statusText.text = CaptureStatus.read()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLatestPlatoonCsv() {
        val directory = File(filesDir, GuildMembersCsvWriter.OUTPUT_DIRECTORY)
        val latest = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
            .maxByOrNull(File::lastModified)
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
        renderHistoryEntries(
            container = historyContainer,
            entries = historyStore.list(),
            selectedIds = selectedHistoryIds,
            saved = false,
            emptyMessage = R.string.no_parsed_packets,
        )
        renderHistoryEntries(
            container = savedHistoryContainer,
            entries = savedHistoryStore.list(),
            selectedIds = selectedSavedHistoryIds,
            saved = true,
            emptyMessage = R.string.no_saved_packets,
        )
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
        val rowHeight = dp(52)
        val tagWidth = dp(112)
        val tagHeight = dp(32)
        entries.forEach { entry ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, PacketHistoryActivity::class.java)
                                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_ID, entry.id)
                                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_TITLE, entry.title)
                                .putExtra(PacketHistoryActivity.EXTRA_SAVED_ENTRY, saved),
                        )
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(TextView(context).apply {
                    text = PayloadCatalog.tag(entry.payloadType)
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = tagHeight / 2f
                        setColor(Color.rgb(49, 93, 168))
                    }
                    contentDescription = getString(
                        R.string.payload_tag_description,
                        PayloadCatalog.tag(entry.payloadType),
                        entry.payloadType?.toString() ?: getString(R.string.unknown_payload_type),
                    )
                }, LinearLayout.LayoutParams(
                    tagWidth,
                    tagHeight,
                ).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
        }
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
        const val KEY_TARGET_PACKAGE = "target_package"
        const val DEFAULT_TARGET_PACKAGE = "com.haoplay.game.and.exilium"
        const val STATUS_REFRESH_MILLIS = 1_000L
    }
}
