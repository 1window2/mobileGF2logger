package dev.gf2log.app

import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import dev.gf2log.app.standby.StandbyAutomation
import dev.gf2log.app.standby.StandbyAutomationService
import dev.gf2log.app.standby.StandbyBatteryPrompt
import dev.gf2log.app.standby.StandbyOutcome
import dev.gf2log.app.standby.StandbyPlanner
import dev.gf2log.app.standby.StandbyProfiles
import dev.gf2log.app.standby.StandbyResults
import dev.gf2log.app.standby.StandbyServer
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import java.time.Instant
import java.time.ZoneId

/**
 * Home's standby entry: the one-time battery prompt, the automatic-recording prerequisites, and
 * the server choice, ending with the armed plan and the standby screen.
 */
internal class StandbyFlow(
    private val activity: BaseActivity,
    private val work: BackgroundWork,
) {
    private var pendingServers: List<StandbyServer> = emptyList()
    private var pendingPackages: Map<String, String> = emptyMap()
    private var resultsDialog: AlertDialog? = null

    /** Standby opens once the system battery-optimization dialog closes, whatever was chosen. */
    private val batteryExemption = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        prepareRecording()
    }

    private val vpnConsent = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Home recreated behind the system dialog lost the list: read it again.
            if (pendingServers.isEmpty()) prepareRecording() else chooseServers(pendingServers, pendingPackages)
        } else {
            Feedback.show(activity, R.string.standby_vpn_needed, long = true)
            openStandby()
        }
    }

    fun start() {
        if (!StandbyBatteryPrompt.shouldAsk(activity)) return prepareRecording()
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.standby_battery_title)
                .setMessage(R.string.standby_battery_message)
                .setNegativeButton(R.string.standby_battery_later) { _, _ ->
                    StandbyBatteryPrompt.markAsked(activity)
                    prepareRecording()
                }
                .setPositiveButton(R.string.standby_battery_allow) { _, _ ->
                    StandbyBatteryPrompt.markAsked(activity)
                    // Some phones have no such dialog; the exemption is a help, not a requirement.
                    runCatching { batteryExemption.launch(StandbyBatteryPrompt.requestIntent(activity)) }
                        .onFailure { prepareRecording() }
                },
        )
    }

    /**
     * Shows the last standby session's results when Home opens; they count as seen only once the
     * user closes them, so a Home briefly resumed during automation cannot use them up.
     */
    fun showUnseenResults() {
        if (StandbyAutomation.isArmed || resultsDialog?.isShowing == true) return
        val results = StandbyResults.unseen(activity)
        if (results.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val lines = results.joinToString("\n") { result ->
            activity.getString(
                R.string.standby_result_line,
                Labels.region(activity, result.region),
                activity.getString(outcomeLabel(result.outcome)),
                Labels.format(Labels.DATE_TIME, Instant.ofEpochMilli(result.atMillis), zone),
            )
        }
        resultsDialog = Dialogs.message(activity, activity.getString(R.string.standby_results_title), lines).apply {
            setOnDismissListener {
                StandbyResults.markSeen(activity, results)
                resultsDialog = null
            }
        }
    }

    private fun prepareRecording() {
        work.run(task = { StandbyProfiles.load(activity) }) { result ->
            val profiles = result.getOrDefault(emptyList())
            if (profiles.isEmpty()) {
                Feedback.show(activity, R.string.standby_no_platoons, long = true)
                return@run openStandby()
            }
            val servers = StandbyPlanner.servers(profiles)
            val packages = profiles.associate { it.storageId to it.packageName }
            if (!StandbyAutomationService.isEnabled(activity)) return@run askForAccessibility()
            val consent = VpnService.prepare(activity)
            if (consent != null) {
                pendingServers = servers
                pendingPackages = packages
                return@run vpnConsent.launch(consent)
            }
            chooseServers(servers, packages)
        }
    }

    private fun askForAccessibility() {
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.standby_accessibility_title)
                .setMessage(R.string.standby_accessibility_message)
                .setNegativeButton(R.string.standby_only) { _, _ -> openStandby() }
                .setPositiveButton(R.string.standby_accessibility_open) { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                },
        )
    }

    /** One server is armed directly; several are offered every time, one per client at most. */
    private fun chooseServers(servers: List<StandbyServer>, packages: Map<String, String>) {
        if (servers.size == 1) return arm(servers, packages)
        StandbyServerDialog.show(
            activity,
            servers,
            preselected = rememberedKeys(),
            message = R.string.standby_servers_message,
            confirm = R.string.standby_start,
        ) { chosen -> arm(chosen, packages) }
    }

    private fun arm(chosen: List<StandbyServer>, packages: Map<String, String>) {
        remember(chosen)
        val rehearsal = rehearsal()
        val now = Instant.now()
        val plan = rehearsal?.let { StandbyPlanner.rehearsal(chosen, now, java.time.Duration.ofMinutes(it.minutes)) }
            ?: StandbyPlanner.plan(chosen, now)
        StandbyAutomation.arm(activity, plan, packages, lockWhenDone = rehearsal?.lock ?: true)
        openStandby()
    }

    private class Rehearsal(val minutes: Long, val lock: Boolean)

    /**
     * A debug build with `files/standby_rehearsal` holding a number of minutes moves the plan so
     * the first reset falls that far from now, for testing on a device; a second word `unlocked`
     * leaves the phone unlocked at the end.
     */
    private fun rehearsal(): Rehearsal? {
        val debuggable = activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return null
        val words = runCatching { java.io.File(activity.filesDir, REHEARSAL_FILE).readText().trim().split(Regex("\\s+")) }
            .getOrNull() ?: return null
        val minutes = words.first().toLongOrNull() ?: return null
        return Rehearsal(minutes, lock = "unlocked" !in words)
    }

    private fun openStandby() {
        activity.startActivity(Intent(activity, StandbyActivity::class.java))
    }

    private fun rememberedKeys(): Set<String> =
        prefs().getStringSet(KEY_SELECTED, emptySet()).orEmpty()

    private fun remember(chosen: List<StandbyServer>) {
        prefs().edit { putStringSet(KEY_SELECTED, chosen.mapTo(HashSet(), StandbyServer::key)) }
    }

    private fun prefs() = activity.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun outcomeLabel(outcome: StandbyOutcome): Int = when (outcome) {
        StandbyOutcome.RECORDED -> R.string.standby_outcome_recorded
        StandbyOutcome.OTHER_PLATOON -> R.string.standby_outcome_other_platoon
        StandbyOutcome.TIMED_OUT -> R.string.standby_outcome_timed_out
        StandbyOutcome.MISSED -> R.string.standby_outcome_missed
        StandbyOutcome.NO_ACCESSIBILITY -> R.string.standby_outcome_no_accessibility
        StandbyOutcome.NO_VPN_CONSENT -> R.string.standby_outcome_no_vpn
        StandbyOutcome.NOT_INSTALLED -> R.string.standby_outcome_not_installed
        StandbyOutcome.CAPTURE_FAILED -> R.string.standby_outcome_capture_failed
        StandbyOutcome.CANCELLED -> R.string.standby_outcome_cancelled
        StandbyOutcome.SKIPPED_IN_USE -> R.string.standby_outcome_in_use
        StandbyOutcome.SCREEN_LOCK_SET -> R.string.standby_outcome_screen_lock
        StandbyOutcome.LOCK_SCREEN_STAYED -> R.string.standby_outcome_lock_screen
    }

    private companion object {
        const val PREFS = "standby_mode"
        const val KEY_SELECTED = "selected_servers"
        const val REHEARSAL_FILE = "standby_rehearsal"
    }
}
