package dev.gf2log.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.app.settings.CapturePreferences
import dev.gf2log.app.capture.CaptureDiagnosticsStore
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.management.BackupFileName
import dev.gf2log.app.management.PlatoonBackupManager
import dev.gf2log.protocol.Gfl2PayloadDecoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.concurrent.Executors
import dev.gf2log.protocol.PayloadCatalog

class OptionsActivity : LocalizedActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileIoExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2FullBackup")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.payload_options)
        setContentView(buildContentView())
    }

    override fun onDestroy() {
        fileIoExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Uses the platform document picker without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_FULL_BACKUP_EXPORT -> runBackupOperation(
                successMessage = R.string.full_backup_exported,
                failureMessage = R.string.full_backup_export_failed,
            ) {
                val output = TrustedExportDestination.openOutputStream(contentResolver, uri)
                    ?: error("Document provider did not open an output stream")
                output.use { PlatoonBackupManager(this).exportFull(it) }
            }
            REQUEST_FULL_BACKUP_RESTORE -> {
                if (!hasBackupExtension(uri)) {
                    showBackupMessage(R.string.invalid_full_backup)
                    return
                }
                runBackupOperation(
                    successMessage = R.string.full_backup_restored,
                    failureMessage = R.string.invalid_full_backup,
                ) {
                    val input = TrustedImportSource.openInputStream(contentResolver, uri)
                        ?: error("Document provider did not open an input stream")
                    input.use { PlatoonBackupManager(this).restoreFull(it) }
                }
            }
        }
    }

    private fun buildContentView(): ScrollView {
        val spacing = dp(16)
        val preferences = PayloadHistoryPreferences(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)

            addView(TextView(context).apply {
                text = getString(R.string.payload_options)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.payload_options_description)
                textSize = 15f
                setPadding(0, dp(8), 0, spacing)
            }, matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.language)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            }, matchWidth())
            addView(RadioGroup(context).apply {
                orientation = RadioGroup.HORIZONTAL
                val current = LanguagePreferences.get(context)
                addView(RadioButton(context).apply {
                    text = getString(R.string.language_english)
                    isChecked = current == LanguagePreferences.DEFAULT_LANGUAGE
                    setOnClickListener { changeLanguage(LanguagePreferences.DEFAULT_LANGUAGE) }
                })
                addView(RadioButton(context).apply {
                    text = getString(R.string.language_korean)
                    isChecked = current == LanguagePreferences.KOREAN
                    setOnClickListener { changeLanguage(LanguagePreferences.KOREAN) }
                })
            }, matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.backup)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(4))
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.restore_full_backup)
                setOnClickListener { confirmFullRestore() }
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.restore_full_backup_description)
                textSize = 14f
                setPadding(dp(16), 0, 0, spacing)
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.back_up_all_information)
                setOnClickListener { exportFullBackup() }
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.back_up_all_information_description)
                textSize = 14f
                setPadding(dp(16), 0, 0, spacing)
            }, matchWidth())

            addView(TextView(context).apply {
                text = getString(R.string.payload_history)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(4))
            }, matchWidth())

            val capturePreferences = CapturePreferences(context)
            addView(CheckBox(context).apply {
                text = getString(R.string.detailed_notifications)
                isChecked = capturePreferences.detailedNotifications
                setOnCheckedChangeListener { _, enabled ->
                    capturePreferences.detailedNotifications = enabled
                }
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.notification_required_explanation)
                textSize = 13f
                setPadding(dp(48), 0, 0, spacing)
            }, matchWidth())

            PayloadCatalog.categories.forEach { category ->
                addView(CheckBox(context).apply {
                    text = getString(
                        R.string.payload_option_label,
                        payloadName(category.payloadType),
                        category.payloadType,
                    )
                    textSize = 17f
                    isChecked = preferences.isEnabled(category.payloadType)
                    isEnabled = !category.isRequired
                    if (category.isRequired) {
                        buttonTintList = ColorStateList(
                            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                            intArrayOf(Color.rgb(49, 93, 168), Color.rgb(49, 93, 168)),
                        )
                    }
                    setOnCheckedChangeListener { _, enabled ->
                        preferences.setEnabled(category.payloadType, enabled)
                    }
                }, matchWidth())
                addView(TextView(context).apply {
                    text = if (category.isRequired) {
                        getString(
                            R.string.required_payload_description,
                            payloadDescription(category.payloadType),
                        )
                    } else {
                        payloadDescription(category.payloadType)
                    }
                    textSize = 14f
                    setPadding(dp(48), 0, 0, spacing)
                }, matchWidth())
            }

            addView(TextView(context).apply {
                text = getString(R.string.last_capture_diagnostics)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, spacing, 0, dp(4))
            }, matchWidth())
            addView(TextView(context).apply {
                val diagnostics = CaptureDiagnosticsStore(context).read()
                text = if (diagnostics == null) {
                    getString(R.string.no_capture_diagnostics)
                } else {
                    getString(
                        R.string.capture_diagnostics,
                        diagnostics.stoppedAt.atZone(ZoneId.systemDefault()).format(DIAGNOSTIC_TIME),
                        diagnostics.forwardedBytes / 1024,
                        diagnostics.inspectedBytes / 1024,
                        diagnostics.decodedPayloads,
                        diagnostics.warnings,
                        diagnostics.droppedChunks,
                        diagnostics.unknownPayloads.ifBlank { getString(R.string.none) },
                    )
                }
                setTextIsSelectable(true)
            }, matchWidth())
        }
        return ScrollView(this).apply { addView(container, matchWidth()) }
    }

    private fun confirmFullRestore() {
        if (CaptureStatus.isRunning) {
            showBackupMessage(R.string.stop_capture_before_backup)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.restore_full_backup)
            .setMessage(R.string.restore_full_backup_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_full_backup) { _, _ -> openFullBackup() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun openFullBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, BACKUP_MIME_TYPES)
        startActivityForResult(intent, REQUEST_FULL_BACKUP_RESTORE)
    }

    @Suppress("DEPRECATION")
    private fun exportFullBackup() {
        if (CaptureStatus.isRunning) {
            showBackupMessage(R.string.stop_capture_before_backup)
            return
        }
        val title = "mobileGF2logger-full-${BACKUP_TIME.format(LocalDateTime.now())}." +
            PlatoonBackupManager.FILE_EXTENSION
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PlatoonBackupManager.MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, title)
        startActivityForResult(intent, REQUEST_FULL_BACKUP_EXPORT)
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

    private fun runBackupOperation(
        successMessage: Int,
        failureMessage: Int,
        operation: () -> Unit,
    ) {
        fileIoExecutor.execute {
            val message = if (runCatching(operation).isSuccess) successMessage else failureMessage
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    showBackupMessage(message)
                    if (message == R.string.full_backup_restored) recreate()
                }
            }
        }
    }

    private fun showBackupMessage(message: Int) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun changeLanguage(language: String) {
        if (LanguagePreferences.get(this) == language) return
        LanguagePreferences.set(this, language)
        recreate()
    }

    private fun payloadName(payloadType: Int): String = getString(
        when (payloadType) {
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> R.string.payload_name_platoon_members
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY -> R.string.payload_name_platoon_activity
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES -> R.string.payload_name_platoon_updates
            Gfl2PayloadDecoder.TYPE_WEAPONS -> R.string.payload_name_weapons
            Gfl2PayloadDecoder.TYPE_ATTACHMENTS -> R.string.payload_name_attachments
            Gfl2PayloadDecoder.TYPE_COMMON_KEYS -> R.string.payload_name_common_keys
            Gfl2PayloadDecoder.TYPE_FORMATIONS -> R.string.payload_name_formations
            else -> R.string.unknown_payload_type
        },
    )

    private fun payloadDescription(payloadType: Int): String = getString(
        when (payloadType) {
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> R.string.payload_description_platoon_members
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY ->
                R.string.payload_description_platoon_activity
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES ->
                R.string.payload_description_platoon_updates
            Gfl2PayloadDecoder.TYPE_WEAPONS -> R.string.payload_description_weapons
            Gfl2PayloadDecoder.TYPE_ATTACHMENTS -> R.string.payload_description_attachments
            Gfl2PayloadDecoder.TYPE_COMMON_KEYS -> R.string.payload_description_common_keys
            Gfl2PayloadDecoder.TYPE_FORMATIONS -> R.string.payload_description_formations
            else -> R.string.unknown_payload_type
        },
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private val DIAGNOSTIC_TIME = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")
        private val BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val BACKUP_MIME_TYPES = arrayOf(
            PlatoonBackupManager.MIME_TYPE,
            "application/zip",
            "application/octet-stream",
        )
        private const val REQUEST_FULL_BACKUP_EXPORT = 301
        private const val REQUEST_FULL_BACKUP_RESTORE = 302
    }
}
