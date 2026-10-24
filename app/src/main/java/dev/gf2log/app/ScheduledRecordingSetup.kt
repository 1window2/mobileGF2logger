package dev.gf2log.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.standby.ScheduledRecording
import dev.gf2log.app.standby.StandbyAutomationService
import dev.gf2log.app.standby.StandbyBatteryPrompt
import dev.gf2log.app.standby.StandbyPlanner
import dev.gf2log.app.standby.StandbyProfiles
import dev.gf2log.app.standby.StandbyServer
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.switchRow
import java.time.ZoneId

/**
 * Settings' scheduled-recording rows and the steps to turn it on: accessibility, VPN consent,
 * exact alarms, the battery exemption, and the server choice. It is offered only on a phone
 * without a PIN, pattern, or password; any step left undone leaves it off.
 */
internal class ScheduledRecordingSetup(
    private val activity: BaseActivity,
    private val work: BackgroundWork,
) {
    private var servers: List<StandbyServer> = emptyList()

    private val vpnConsent = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) checkExactAlarms() else stop(R.string.standby_vpn_needed)
    }

    /** Whatever was chosen in the system dialog, the next step follows. */
    private val batteryExemption = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        chooseServers()
    }

    fun view(): View {
        val enabled = ScheduledRecording.isEnabled(activity)
        val blocked = ScheduledRecording.isBlockedByScreenLock(activity)
        return activity.group(listOf(
            activity.switchRow(
                title = activity.getString(R.string.scheduled_switch),
                detail = status(enabled, blocked),
                checked = enabled && !blocked,
                // Turning it off always works; turning it on needs a phone without a screen lock.
                enabled = !blocked || enabled,
            ) { on -> if (on) turnOn() else turnOff() },
            activity.navigationRow(
                activity.getString(R.string.scheduled_servers),
                chosenSummary(),
                R.drawable.ic_calendar,
                enabled = enabled && !blocked,
            ) { turnOn() },
        ))
    }

    private fun status(enabled: Boolean, blocked: Boolean): String = when {
        blocked -> activity.getString(R.string.scheduled_blocked)
        !enabled -> activity.getString(R.string.scheduled_off)
        else -> {
            val next = ScheduledRecording.next(activity)?.let(::nextText) ?: activity.getString(R.string.scheduled_nothing)
            if (ScheduledRecording.canScheduleExactAlarms(activity)) {
                next
            } else {
                next + "\n" + activity.getString(R.string.scheduled_inexact)
            }
        }
    }

    private fun nextText(next: ScheduledRecording.Next): String = activity.getString(
        R.string.scheduled_next,
        next.regions.joinToString(", ") { Labels.region(activity, it) },
        Labels.format(Labels.DATE_TIME, next.startAt, ZoneId.systemDefault()),
    )

    private fun chosenSummary(): String? = ScheduledRecording.selectedKeys(activity)
        .map { key -> GameServerRegion.fromStored(key.split('|').getOrNull(1)) }
        .sortedBy(GameServerRegion::ordinal)
        .joinToString(", ") { Labels.region(activity, it) }
        .ifBlank { null }

    private fun turnOff() {
        ScheduledRecording.disable(activity)
        refresh()
    }

    private fun turnOn() {
        if (ScheduledRecording.isBlockedByScreenLock(activity)) return stop(R.string.scheduled_blocked)
        work.run(task = { StandbyPlanner.servers(StandbyProfiles.load(activity)) }) { result ->
            servers = result.getOrDefault(emptyList())
            when {
                servers.isEmpty() -> stop(R.string.standby_no_platoons)
                !StandbyAutomationService.isEnabled(activity) -> askForAccessibility()
                else -> VpnService.prepare(activity)?.let(vpnConsent::launch) ?: checkExactAlarms()
            }
        }
    }

    private fun askForAccessibility() {
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.standby_accessibility_title)
                .setMessage(R.string.standby_accessibility_message)
                .setOnCancelListener { refresh() }
                .setNegativeButton(R.string.cancel) { _, _ -> refresh() }
                .setPositiveButton(R.string.standby_accessibility_open) { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    refresh()
                },
        )
    }

    private fun checkExactAlarms() {
        if (ScheduledRecording.canScheduleExactAlarms(activity)) return checkBattery()
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.scheduled_exact_title)
                .setMessage(R.string.scheduled_exact_message)
                .setOnCancelListener { refresh() }
                .setNegativeButton(R.string.cancel) { _, _ -> refresh() }
                .setPositiveButton(R.string.standby_accessibility_open) { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            activity.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${activity.packageName}".toUri()),
                            )
                        }
                    }
                    refresh()
                },
        )
    }

    private fun checkBattery() {
        if (StandbyBatteryPrompt.isExempt(activity)) return chooseServers()
        StandbyBatteryPrompt.markAsked(activity)
        // Some phones have no such dialog; the exemption is a help, not a requirement.
        runCatching { batteryExemption.launch(StandbyBatteryPrompt.requestIntent(activity)) }.onFailure { chooseServers() }
    }

    private fun chooseServers() {
        // A Settings screen recreated behind a system dialog lost the list: read it again.
        if (servers.isEmpty()) {
            return work.run(task = { StandbyPlanner.servers(StandbyProfiles.load(activity)) }) { result ->
                servers = result.getOrDefault(emptyList())
                if (servers.isEmpty()) stop(R.string.standby_no_platoons) else chooseServers()
            }
        }
        if (servers.size == 1) return save(servers)
        StandbyServerDialog.show(
            activity,
            servers,
            preselected = ScheduledRecording.selectedKeys(activity),
            message = R.string.scheduled_servers_message,
            confirm = R.string.scheduled_confirm,
            // Closing the choice without saving leaves everything as it was.
            onCancel = { refresh() },
        ) { chosen -> save(chosen) }
    }

    private fun save(chosen: List<StandbyServer>) {
        ScheduledRecording.enable(activity, chosen) { next ->
            Feedback.show(activity, next?.let(::nextText) ?: activity.getString(R.string.scheduled_nothing), long = true)
            refresh()
        }
    }

    /** Rebuilds Settings so the switch and status show the stored state. */
    private fun refresh() {
        if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
    }

    private fun stop(message: Int) {
        Feedback.show(activity, message, long = true)
        refresh()
    }
}
