package dev.gf2log.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import dev.gf2log.app.capture.CaptureDiagnosticsStore
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.discord.DiscordWebhookSecretStore
import dev.gf2log.app.management.BackupFileName
import dev.gf2log.app.management.InvalidBackupException
import dev.gf2log.app.management.PlatoonBackupManager
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.settings.CapturePreferences
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.switchRow
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField
import dev.gf2log.protocol.PayloadCatalog
import java.time.LocalDateTime
import java.time.ZoneId

/** Settings: grouped rows for the Platoon, display, capture, backup, sharing, and about. */
class OptionsActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private val work = BackgroundWork(this, "GF2Settings")
    private var pendingExportComplete = false

    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument(PlatoonBackupManager.MIME_TYPE),
    ) { uri ->
        val scope = profileBinding.scope
        if (uri == null || scope == null) return@registerForActivityResult
        val complete = pendingExportComplete
        runBackup(
            progress = R.string.backup_exporting,
            success = if (complete) R.string.full_backup_exported else R.string.status_backup_exported,
            failure = { R.string.full_backup_export_failed },
            reload = false,
        ) {
            DocumentAccess.openOutput(contentResolver, uri).use { output ->
                val manager = PlatoonBackupManager(this, scope)
                if (complete) manager.exportFull(output) else manager.export(output)
            }
        }
    }

    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::restore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        pendingExportComplete = savedInstanceState?.getBoolean(STATE_EXPORT_COMPLETE) == true
        val screen = Screen(this)
        screen.topBar.setNavigation(::finish).setTitle(getString(R.string.payload_options))
        screen.setBody(Screen.scrollingBody(this, buildContent()))
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPORT_COMPLETE, pendingExportComplete)
        super.onSaveInstanceState(outState)
    }

    private fun buildContent(): LinearLayout = column {
        val scope = profileBinding.scope
        addView(sectionHeader(getString(R.string.settings_platoon)), lp())
        addView(PlatoonProfileSelector.card(this@OptionsActivity), lp())
        addView(group(listOf(
            navigationRow(
                getString(R.string.manage_platoon_profiles),
                getString(R.string.manage_platoon_profiles_detail),
                R.drawable.ic_group,
            ) { PlatoonProfileSelector.showManagement(this@OptionsActivity) },
            navigationRow(
                getString(R.string.server_region),
                scope?.let { resetSummary(it.storageId) } ?: getString(R.string.no_platoon_detected),
                R.drawable.ic_calendar,
                enabled = scope != null,
            ) { scope?.let(::chooseResetRegion) },
        )), lp(top = 8))

        addView(sectionHeader(getString(R.string.settings_display)), lp())
        addView(panel(padding = 12) {
            addView(text(getString(R.string.language), TextRole.LABEL), lp())
            addView(Controls.segmentedControl(
                context = this@OptionsActivity,
                options = listOf(
                    LanguagePreferences.KOREAN to getString(R.string.language_korean_native),
                    LanguagePreferences.DEFAULT_LANGUAGE to getString(R.string.language_english),
                ),
                selectedValue = LanguagePreferences.get(this@OptionsActivity),
            ) { language ->
                if (LanguagePreferences.get(this@OptionsActivity) != language) {
                    LanguagePreferences.set(this@OptionsActivity, language)
                    recreate()
                }
            }, lp(top = 6))
            addView(text(getString(R.string.appearance), TextRole.LABEL), lp(top = 16))
            addView(Controls.segmentedControl(
                context = this@OptionsActivity,
                options = listOf(
                    ThemePreferences.SYSTEM to getString(R.string.theme_system),
                    ThemePreferences.LIGHT to getString(R.string.theme_light),
                    ThemePreferences.DARK to getString(R.string.theme_dark),
                ),
                selectedValue = ThemePreferences.get(this@OptionsActivity),
            ) { mode ->
                if (ThemePreferences.get(this@OptionsActivity) != mode) {
                    ThemePreferences.set(this@OptionsActivity, mode)
                    recreate()
                }
            }, lp(top = 6))
        }, lp())

        addView(sectionHeader(getString(R.string.settings_capture)), lp())
        addView(caption(getString(R.string.settings_capture_servers_detail)), lp())
        addView(group(listOf(SupportedGamePackages.HAOPLAY, SupportedGamePackages.DARKWINTER).map { packageName ->
            navigationRow(
                getString(captureRegionTitle(packageName)),
                ClientServerRegionPreferences(this@OptionsActivity).configured(packageName)
                    ?.let { Labels.region(this@OptionsActivity, it) }
                    ?: getString(R.string.server_region_not_configured),
                R.drawable.ic_shield,
            ) { chooseCaptureRegion(packageName) }
        }), lp())
        val capturePreferences = CapturePreferences(this@OptionsActivity)
        val payloads = PayloadHistoryPreferences(this@OptionsActivity)
        addView(panel(padding = 8) {
            addView(switchRow(
                getString(R.string.detailed_notifications),
                getString(R.string.notification_required_explanation),
                capturePreferences.detailedNotifications,
            ) { capturePreferences.detailedNotifications = it }, lp())
            addView(text(getString(R.string.payload_history), TextRole.LABEL) {
                setPadding(dp(4), dp(20), dp(4), dp(2))
            }, lp())
            addView(caption(getString(R.string.payload_options_description)), lp())
            PayloadCatalog.categories.forEach { category ->
                addView(switchRow(
                    title = Labels.payloadName(this@OptionsActivity, category.payloadType),
                    detail = Labels.payloadDescription(this@OptionsActivity, category.payloadType),
                    checked = category.isRequired || payloads.isEnabled(category.payloadType),
                    enabled = !category.isRequired,
                    badge = getString(R.string.required).takeIf { category.isRequired },
                ) { payloads.setEnabled(category.payloadType, it) }, lp())
            }
        }, lp(top = 8))

        addView(sectionHeader(getString(R.string.backup)), lp())
        addView(caption(getString(R.string.settings_backup_detail)), lp())
        addView(group(listOf(
            navigationRow(
                getString(R.string.export_platoon_backup),
                getString(R.string.export_platoon_backup_detail),
                R.drawable.ic_download,
                enabled = scope != null,
            ) { startExport(complete = false) },
            navigationRow(
                getString(R.string.back_up_all_information),
                getString(R.string.back_up_all_information_description),
                R.drawable.ic_save,
                enabled = scope != null,
            ) { startExport(complete = true) },
            navigationRow(getString(R.string.restore_full_backup), getString(R.string.restore_backup_detail), R.drawable.ic_upload) {
                confirmRestore()
            },
        )), lp())

        addView(sectionHeader(getString(R.string.discord_webhook)), lp())
        val webhookRow = column()
        addView(webhookRow, lp())
        renderWebhookRow(webhookRow)

        addView(sectionHeader(getString(R.string.settings_about)), lp())
        addView(group(listOf(
            navigationRow(
                getString(R.string.last_capture_diagnostics),
                getString(R.string.last_capture_diagnostics_detail),
                R.drawable.ic_bug,
            ) { showDiagnostics() },
            navigationRow(getString(R.string.show_welcome_guide), null, R.drawable.ic_info_outline) {
                startActivity(
                    Intent(this@OptionsActivity, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REPLAY, true),
                )
            },
            navigationRow(getString(R.string.app_version), versionName(), R.drawable.ic_code, showChevron = false) {},
        )), lp())
    }

    private fun caption(value: String) = text(value, TextRole.CAPTION) { setPadding(dp(4), 0, dp(4), dp(8)) }

    private fun captureRegionTitle(packageName: String) =
        if (packageName == SupportedGamePackages.HAOPLAY) R.string.haoplay_capture_region else R.string.darkwinter_capture_region

    // region Server reset

    private fun resetSummary(storageId: String): String {
        val region = GameTimeZonePreferences.region(this, storageId)
        val zone = GameTimeZonePreferences.deviceZone()
        if (region == GameServerRegion.MANUAL) {
            return getString(R.string.manual_reset_region_summary, GameTimeZonePreferences.get(this, storageId).id, zone.id)
        }
        val resolved = getString(
            R.string.server_reset_summary,
            Labels.region(this, region),
            Labels.format(Labels.TIME, region.nextReset(), zone),
            zone.id,
        )
        return if (GameTimeZonePreferences.isAutomatic(this, storageId)) {
            getString(R.string.auto_server_reset_summary, resolved)
        } else {
            resolved
        }
    }

    private fun chooseResetRegion(scope: PlatoonStorageScope) {
        val regions = GameServerRegion.entries.filterNot { it == GameServerRegion.MANUAL }
        val automatic = GameTimeZonePreferences.isAutomatic(this, scope.storageId)
        val current = GameTimeZonePreferences.region(this, scope.storageId)
        val checked = if (automatic) 0 else regions.indexOf(current).takeIf { it >= 0 }?.plus(1) ?: -1
        Dialogs.choose(
            this,
            getString(R.string.server_region),
            listOf(getString(R.string.server_region_auto)) + regions.map { Labels.region(this, it) },
            checked,
        ) { which ->
            val selected = if (which == 0) null else regions[which - 1]
            val changed = if (selected == null) !automatic else automatic || selected != current
            if (changed) updateResetRegion(scope, selected)
        }
    }

    private fun updateResetRegion(scope: PlatoonStorageScope, region: GameServerRegion?) {
        Feedback.show(this, R.string.rebuilding_weekly_history)
        work.run(
            task = {
                val previousAutomatic = GameTimeZonePreferences.isAutomatic(this, scope.storageId)
                val previousRegion = GameTimeZonePreferences.region(this, scope.storageId)
                if (region == null) {
                    GameTimeZonePreferences.clearRegionOverride(this, scope.storageId)
                } else {
                    GameTimeZonePreferences.setRegion(this, region, scope.storageId)
                }
                try {
                    PlatoonRepository(this, scope)
                        .rebuildWeeklyHistoryForTimeZoneChange(GameTimeZonePreferences.get(this, scope.storageId))
                } catch (error: Exception) {
                    runCatching {
                        if (previousAutomatic) {
                            GameTimeZonePreferences.clearRegionOverride(this, scope.storageId)
                        } else {
                            GameTimeZonePreferences.setRegion(this, previousRegion, scope.storageId)
                        }
                    }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
            },
        ) { result ->
            Feedback.show(this, if (result.isSuccess) R.string.game_timezone_updated else R.string.game_timezone_update_failed)
            recreate()
        }
    }

    private fun chooseCaptureRegion(packageName: String) {
        val preferences = ClientServerRegionPreferences(this)
        val regions = preferences.allowed(packageName)
        Dialogs.choose(
            this,
            getString(captureRegionTitle(packageName)),
            regions.map { Labels.region(this, it) },
            regions.indexOf(preferences.configured(packageName)),
        ) { which ->
            preferences.set(packageName, regions[which])
            recreate()
        }
    }

    // endregion

    // region Backup

    private fun startExport(complete: Boolean) {
        if (CaptureStatus.isRunning) {
            Feedback.show(this, R.string.stop_capture_before_backup, long = true)
            return
        }
        pendingExportComplete = complete
        val prefix = if (complete) "GF2logger-full" else "GF2logger-platoon"
        exportBackup.launch("$prefix-${Labels.FILE_STAMP.format(LocalDateTime.now())}.${PlatoonBackupManager.FILE_EXTENSION}")
    }

    private fun confirmRestore() {
        if (CaptureStatus.isRunning) {
            Feedback.show(this, R.string.stop_capture_before_backup, long = true)
            return
        }
        Dialogs.confirm(this, R.string.restore_full_backup, R.string.restore_backup_warning, R.string.choose_file) {
            restoreBackup.launch(arrayOf(PlatoonBackupManager.MIME_TYPE, "application/zip", "application/octet-stream"))
        }
    }

    private fun restore(uri: Uri) {
        if (DocumentAccess.displayName(contentResolver, uri)?.let(BackupFileName::isValid) != true) {
            Feedback.show(this, R.string.invalid_full_backup, long = true)
            return
        }
        runBackup(R.string.backup_restoring, R.string.full_backup_restored, ::fullBackupRestoreFailureMessage, reload = true) {
            DocumentAccess.openInput(contentResolver, uri).use { input ->
                PlatoonBackupManager.restoreSelected(this, input, complete = true)
            }
        }
    }

    private fun runBackup(
        progress: Int,
        success: Int,
        failure: (Throwable) -> Int,
        reload: Boolean,
        operation: () -> Unit,
    ) {
        Feedback.show(this, progress)
        work.run(task = operation) { result ->
            Feedback.show(this, result.fold(onSuccess = { success }, onFailure = failure), long = true)
            if (result.isSuccess && reload) recreate()
        }
    }

    // endregion

    // region Discord, diagnostics, about

    private fun renderWebhookRow(container: LinearLayout) {
        work.run(task = { DiscordWebhookSecretStore(this).read() != null }) { result ->
            val configured = result.getOrDefault(false)
            container.removeAllViews()
            container.addView(group(listOf(
                navigationRow(
                    getString(if (configured) R.string.discord_webhook_configured else R.string.discord_webhook_not_configured),
                    getString(R.string.discord_webhook_summary),
                    R.drawable.ic_discord,
                    trailing = getString(if (configured) R.string.change else R.string.set_up),
                ) { editWebhook(container, configured) },
            )), lp())
        }
    }

    private fun editWebhook(container: LinearLayout, configured: Boolean) {
        val input = textField(
            getString(R.string.discord_webhook_hint),
            id = R.id.webhook_input,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val error = text(null, TextRole.CAPTION) {
            setTextColor(getColor(R.color.destructive_action))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        val builder = Dialogs.builder(this)
            .setTitle(R.string.discord_webhook)
            .setView(Dialogs.content(this) {
                addView(text(getString(R.string.discord_webhook_description), TextRole.SECONDARY), lp())
                addView(input, lp(top = 12))
                addView(error, lp(top = 4))
            })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_discord_webhook, null)
        if (configured) builder.setNeutralButton(R.string.clear_discord_webhook, null)
        Dialogs.show(builder) { dialog ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                work.run(task = { DiscordWebhookSecretStore(this).save(value) }) { result ->
                    if (result.isSuccess) {
                        dialog.dismiss()
                        Feedback.show(this, R.string.discord_webhook_saved)
                        renderWebhookRow(container)
                    } else {
                        error.text = getString(R.string.discord_webhook_invalid)
                        error.visibility = View.VISIBLE
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(getColor(R.color.destructive_action))
                setOnClickListener {
                    work.run(task = { DiscordWebhookSecretStore(this@OptionsActivity).clear() }) { result ->
                        dialog.dismiss()
                        Feedback.show(
                            this@OptionsActivity,
                            if (result.isSuccess) R.string.discord_webhook_cleared else R.string.discord_webhook_clear_failed,
                        )
                        renderWebhookRow(container)
                    }
                }
            }
        }
    }

    private fun showDiagnostics() {
        work.run(task = { CaptureDiagnosticsStore(this).read() }) { result ->
            val diagnostics = result.getOrNull()
            val message = if (diagnostics == null) {
                getString(R.string.no_capture_diagnostics)
            } else {
                getString(
                    R.string.capture_diagnostics,
                    Labels.format(Labels.DATE_TIME_SECONDS, diagnostics.stoppedAt, ZoneId.systemDefault()),
                    diagnostics.forwardedBytes / 1024,
                    diagnostics.inspectedBytes / 1024,
                    diagnostics.decodedPayloads,
                    diagnostics.warnings,
                    diagnostics.droppedChunks,
                    diagnostics.unknownPayloads.ifBlank { getString(R.string.none) },
                )
            }
            val builder = Dialogs.builder(this)
                .setTitle(R.string.last_capture_diagnostics)
                .setMessage(message)
                .setPositiveButton(R.string.done, null)
            if (diagnostics != null) {
                builder.setNeutralButton(R.string.copy) { _, _ ->
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(getString(R.string.last_capture_diagnostics), message))
                    Feedback.show(this, R.string.copied)
                }
            }
            Dialogs.show(builder)
        }
    }

    private fun versionName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    }.getOrNull().orEmpty()

    // endregion

    private companion object {
        const val STATE_EXPORT_COMPLETE = "settings.export_complete"
    }
}

/** Known validation failures blame the selected file; anything else is an operational failure. */
internal fun fullBackupRestoreFailureMessage(error: Throwable): Int =
    if (error is InvalidBackupException) R.string.invalid_full_backup else R.string.full_backup_restore_failed
