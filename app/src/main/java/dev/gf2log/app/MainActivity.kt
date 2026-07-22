package dev.gf2log.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.capture.GuildMembersCsvWriter
import dev.gf2log.app.history.CaptureHistoryStore
import java.io.File

class MainActivity : Activity() {
    private lateinit var packageNameInput: EditText
    private lateinit var statusText: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyStore: CaptureHistoryStore
    private val selectedHistoryIds = linkedSetOf<String>()
    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() {
            statusText.text = CaptureStatus.read(this@MainActivity)
            statusHandler.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }
    private var pendingExport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyStore = CaptureHistoryStore(
            File(filesDir, CaptureHistoryStore.HISTORY_DIRECTORY),
        )
        setContentView(buildContentView())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        statusText.text = CaptureStatus.read(this)
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
            requestCode == REQUEST_EXPORT && resultCode == RESULT_OK && data?.data != null -> {
                val source = pendingExport ?: return
                contentResolver.openOutputStream(data.data!!)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                statusText.text = "Exported ${source.name}"
                pendingExport = null
            }
        }
    }

    private fun buildContentView(): ScrollView {
        val spacing = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)

            addView(TextView(context).apply {
                text = "GF2log"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "On-device protocol capture for one explicitly selected game package. Raw traffic is not stored."
                textSize = 16f
                setPadding(0, spacing / 2, 0, spacing)
            })

            packageNameInput = EditText(context).apply {
                hint = "Installed game package name"
                setSingleLine(true)
                setText(
                    getPreferences(MODE_PRIVATE)
                        .getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE),
                )
            }
            addView(packageNameInput, matchWidth())

            addView(Button(context).apply {
                text = "Prepare capture"
                setOnClickListener { requestVpnAndStart() }
            }, matchWidth())
            addView(Button(context).apply {
                text = "Stop"
                setOnClickListener { stopCaptureService() }
            }, matchWidth())

            statusText = TextView(context).apply {
                text = CaptureStatus.read(context)
                textSize = 15f
                setPadding(0, spacing, 0, 0)
            }
            addView(statusText, matchWidth())

            addView(TextView(context).apply {
                text = "Recent parsed packets (latest 100)"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, spacing / 2)
            }, matchWidth())
            historyContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(historyContainer, matchWidth())
            addView(Button(context).apply {
                text = "Delete selected history"
                setOnClickListener { deleteSelectedHistory() }
            }, matchWidth())
            addView(Button(context).apply {
                text = "Export latest guild CSV"
                setOnClickListener { exportLatestGuildCsv() }
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
        statusText.text = "Preparing capture"
    }

    private fun stopCaptureService() {
        val intent = Intent(this, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_STOP)
        startService(intent)
        statusText.text = "Capture is stopped"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLatestGuildCsv() {
        val directory = File(filesDir, GuildMembersCsvWriter.OUTPUT_DIRECTORY)
        val latest = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
            .maxByOrNull(File::lastModified)
        if (latest == null) {
            statusText.text = "No guild CSV has been captured yet"
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
        historyContainer.removeAllViews()
        val entries = historyStore.list()
        if (entries.isEmpty()) {
            historyContainer.addView(TextView(this).apply {
                text = "No parsed packets captured yet"
            }, matchWidth())
            return
        }
        entries.forEach { entry ->
            historyContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(CheckBox(context).apply {
                    isChecked = entry.id in selectedHistoryIds
                    contentDescription = "Select ${entry.title}"
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedHistoryIds += entry.id else selectedHistoryIds -= entry.id
                    }
                })
                addView(Button(context).apply {
                    text = entry.title
                    isAllCaps = false
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, PacketHistoryActivity::class.java)
                                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_ID, entry.id)
                                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_TITLE, entry.title),
                        )
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWidth())
        }
    }

    private fun deleteSelectedHistory() {
        if (selectedHistoryIds.isEmpty()) {
            statusText.text = "Select one or more history entries first"
            return
        }
        val deleted = historyStore.delete(selectedHistoryIds)
        selectedHistoryIds.clear()
        statusText.text = "Deleted $deleted history ${if (deleted == 1) "entry" else "entries"}"
        refreshHistory()
    }

    private fun matchWidth(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val REQUEST_VPN = 100
        const val REQUEST_NOTIFICATIONS = 101
        const val REQUEST_EXPORT = 102
        const val KEY_TARGET_PACKAGE = "target_package"
        const val DEFAULT_TARGET_PACKAGE = "com.haoplay.game.and.exilium"
        const val STATUS_REFRESH_MILLIS = 1_000L
    }
}
