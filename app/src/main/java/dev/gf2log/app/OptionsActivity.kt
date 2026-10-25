package dev.gf2log.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import dev.gf2log.app.capture.CaptureDiagnosticsStore
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.settings.CapturePreferences
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.app.standby.ScheduledRecording
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
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
import dev.gf2log.protocol.PayloadCatalog
import java.time.ZoneId

/** Settings: grouped rows for the Platoon, display, capture, backup, sharing, and about. */
class OptionsActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private val work = BackgroundWork(this, "GF2Settings")
    private val backup = BackupFlow(this, work, MAINTENANCE) { profileBinding.scope }
    private val scheduled = ScheduledRecordingSetup(this, work)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        backup.restoreState(savedInstanceState)
        val screen = Screen(this)
        screen.topBar.setNavigation(::finish).setTitle(getString(R.string.payload_options))
        screen.setBody(Screen.scrollingBody(this, buildContent()))
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) {
            recreate()
            return
        }
        // Platoons may have changed since the next scheduled recording was planned.
        if (ScheduledRecording.isEnabled(this)) ScheduledRecording.reschedule(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        backup.saveState(outState)
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
                    LanguagePreferences.DEFAULT_LANGUAGE to getString(R.string.language_english),
                    LanguagePreferences.KOREAN to getString(R.string.language_korean_native),
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

        addView(sectionHeader(getString(R.string.scheduled_title)), lp())
        addView(caption(getString(R.string.scheduled_detail)), lp())
        addView(scheduled.view(), lp())

        addView(sectionHeader(getString(R.string.backup)), lp())
        addView(caption(getString(R.string.settings_backup_detail)), lp())
        addView(group(listOf(
            navigationRow(
                getString(R.string.export_platoon_backup),
                getString(R.string.export_platoon_backup_detail),
                R.drawable.ic_download,
                enabled = scope != null,
            ) { backup.export(complete = false) },
            navigationRow(
                getString(R.string.back_up_all_information),
                getString(R.string.back_up_all_information_description),
                R.drawable.ic_save,
                enabled = scope != null,
            ) { backup.export(complete = true) },
            navigationRow(getString(R.string.restore_full_backup), getString(R.string.restore_backup_detail), R.drawable.ic_upload) {
                backup.confirmRestore()
            },
        )), lp())

        addView(sectionHeader(getString(R.string.discord_webhook)), lp())
        addView(DiscordWebhookRow(this@OptionsActivity, work).view, lp())

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
        if (work.isBusy(MAINTENANCE)) return
        Feedback.show(this, R.string.rebuilding_weekly_history)
        work.run(
            key = MAINTENANCE,
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

    // region Diagnostics and about

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
                    Feedback.copy(this, getString(R.string.last_capture_diagnostics), message, R.string.copied)
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
        const val MAINTENANCE = "maintenance"
    }
}
