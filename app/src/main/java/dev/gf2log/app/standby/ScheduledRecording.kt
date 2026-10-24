package dev.gf2log.app.standby

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import dev.gf2log.app.OptionsActivity
import dev.gf2log.app.StandbyActivity
import dev.gf2log.app.capture.MainThread
import dev.gf2log.app.settings.GameServerRegion
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Scheduled recording, for phones without a PIN, pattern, or password: an exact alarm wakes the
 * app five minutes before each chosen server's game day ends; the standby screen turns the screen
 * on, gets past a swipe lock screen, runs the same recording as standby mode, and turns the screen
 * off again. Every day, with no standby screen kept on overnight.
 *
 * Only the on/off state and the chosen server keys are stored. Every wake-up is planned again from
 * the current Platoons, and the next one is set before the current one runs, so one failed night
 * never breaks the chain.
 */
internal object ScheduledRecording {

    const val ACTION_FIRE = "dev.gf2log.action.SCHEDULED_RECORDING"
    const val EXTRA_RESET = "dev.gf2log.extra.SCHEDULED_RESET"
    const val EXTRA_SERVERS = "dev.gf2log.extra.SCHEDULED_SERVERS"

    /** The next planned wake-up, for Settings. */
    data class Next(val startAt: Instant, val regions: List<GameServerRegion>)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "GF2Scheduled") }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun selectedKeys(context: Context): Set<String> = prefs(context).getStringSet(KEY_SERVERS, emptySet()).orEmpty()

    /** A PIN, pattern, or password cannot be passed unattended, so it rules scheduling out. */
    fun isBlockedByScreenLock(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != false

    /** Android 12+ lets the user withhold exact alarms; without them a wake-up may come late. */
    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun next(context: Context): Next? {
        val prefs = prefs(context)
        if (!isEnabled(context) || !prefs.contains(KEY_NEXT_START)) return null
        return Next(
            startAt = Instant.ofEpochMilli(prefs.getLong(KEY_NEXT_START, 0)),
            regions = prefs.getString(KEY_NEXT_REGIONS, null).orEmpty().split(',')
                .filter(String::isNotBlank).map(GameServerRegion::fromStored),
        )
    }

    /** Turns scheduling on for [chosen]; [done] gets the first wake-up on the main thread. */
    fun enable(context: Context, chosen: List<StandbyServer>, done: (Next?) -> Unit) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, true)
            putStringSet(KEY_SERVERS, chosen.mapTo(HashSet(), StandbyServer::key))
        }
        reschedule(context, done)
    }

    fun disable(context: Context) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, false)
            remove(KEY_NEXT_START)
            remove(KEY_NEXT_REGIONS)
        }
        cancelAlarm(context)
    }

    /** Plans and sets the next wake-up from the current Platoons; [done] runs on the main thread. */
    fun reschedule(context: Context, done: ((Next?) -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            val next = runCatching { scheduleNext(app) }
                .onFailure { Log.w(TAG, "Could not schedule the next recording", it) }
                .getOrNull()
            done?.let { callback -> MainThread.post { callback(next) } }
        }
    }

    @WorkerThread
    private fun scheduleNext(context: Context): Next? {
        if (!isEnabled(context)) {
            cancelAlarm(context)
            return null
        }
        if (isBlockedByScreenLock(context)) {
            disable(context)
            return null
        }
        val selected = selectedServers(context, StandbyProfiles.load(context), selectedKeys(context))
        val now = Instant.now()
        val from = StandbyPlanner.scheduleFrom(now, handledReset(context))
        val runs = when {
            selected.isEmpty() -> emptyList()
            else -> takeRehearsal(context)?.let { StandbyPlanner.rehearsal(selected, from, it) }
                ?: StandbyPlanner.plan(selected, from)
        }
        val group = StandbyPlanner.firstGroup(runs)
        if (group.isEmpty()) {
            cancelAlarm(context)
            prefs(context).edit(commit = true) { remove(KEY_NEXT_START); remove(KEY_NEXT_REGIONS) }
            return null
        }
        val start = group.first().startAt
        setAlarm(context, start, group.first().reset, group.map { it.server.key })
        // Turned off while this was being planned: take the alarm back.
        if (!isEnabled(context)) {
            cancelAlarm(context)
            return null
        }
        prefs(context).edit(commit = true) {
            putLong(KEY_NEXT_START, start.toEpochMilli())
            putString(KEY_NEXT_REGIONS, group.joinToString(",") { it.server.region.storedValue })
        }
        Log.i(TAG, "Next scheduled recording at $start for ${group.map { it.server.key }}")
        return Next(start, group.map { it.server.region })
    }

    /**
     * The alarm went off. The next wake-up is planned first; then, unless the phone is locked
     * with a PIN or in use, the standby screen wakes it and takes over.
     */
    fun onAlarm(context: Context, reset: Instant, keys: Set<String>, scheduled: () -> Unit) {
        // A second delivery for the same reset (two reschedules racing) must not run it twice.
        val handled = handledReset(context)
        if (handled != null && !reset.isAfter(handled)) return scheduled()
        prefs(context).edit(commit = true) { putLong(KEY_HANDLED_RESET, reset.toEpochMilli()) }
        reschedule(context) { scheduled() }
        if (!isEnabled(context) || keys.isEmpty()) return
        val outcome = when {
            // Too late to finish before the day ends (an inexact alarm can come hours late).
            !Instant.now().isBefore(reset.plus(StandbyPlanner.GRACE).minus(LATEST_START_MARGIN)) -> StandbyOutcome.MISSED
            // Standby mode is already recording on its own plan.
            StandbyAutomation.isArmed -> StandbyOutcome.SKIPPED_IN_USE
            isBlockedByScreenLock(context) -> StandbyOutcome.SCREEN_LOCK_SET
            isInUse(context) -> StandbyOutcome.SKIPPED_IN_USE
            StandbyAutomationService.instance == null -> StandbyOutcome.NO_ACCESSIBILITY
            else -> null
        }
        if (outcome != null) {
            Log.i(TAG, "Scheduled recording not run: $outcome")
            record(context, keys, outcome)
            if (outcome == StandbyOutcome.SCREEN_LOCK_SET) disable(context)
            return
        }
        val service = requireNotNull(StandbyAutomationService.instance)
        val started = runCatching {
            // The accessibility service may start activities from the background.
            service.startActivity(
                Intent(context, StandbyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_RESET, reset.toEpochMilli())
                    .putExtra(EXTRA_SERVERS, keys.toTypedArray()),
            )
        }
        if (started.isFailure) {
            Log.w(TAG, "Could not open the standby screen", started.exceptionOrNull())
            record(context, keys, StandbyOutcome.CAPTURE_FAILED)
        }
    }

    /**
     * The standby screen is up for a scheduled recording: get past a swipe lock screen, then arm
     * the recording of [keys] for [reset].
     */
    fun begin(activity: StandbyActivity, reset: Instant, keys: Set<String>) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        when {
            keyguard == null || keyguard.isDeviceSecure -> {
                record(activity, keys, StandbyOutcome.SCREEN_LOCK_SET)
                disable(activity)
                activity.standDown()
            }
            keyguard.isKeyguardLocked -> keyguard.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        if (!activity.isGone()) arm(activity, reset, keys)
                    }

                    // Once the screen is gone (the user tapped it), there is nothing to give up.
                    override fun onDismissError() {
                        if (!activity.isGone()) lockScreenStayed(activity, keys)
                    }

                    override fun onDismissCancelled() {
                        if (!activity.isGone()) lockScreenStayed(activity, keys)
                    }
                },
            )
            else -> arm(activity, reset, keys)
        }
    }

    private fun lockScreenStayed(activity: StandbyActivity, keys: Set<String>) {
        record(activity, keys, StandbyOutcome.LOCK_SCREEN_STAYED)
        activity.standDown()
    }

    private fun arm(activity: StandbyActivity, reset: Instant, keys: Set<String>) {
        val app = activity.applicationContext
        worker.execute {
            val profiles = runCatching { StandbyProfiles.load(app) }.getOrDefault(emptyList())
            MainThread.post {
                // The user tapped the screen meanwhile, or a recreated screen armed already.
                if (activity.isGone() || StandbyAutomation.isArmed) return@post
                val servers = selectedServers(app, profiles, keys)
                if (servers.isEmpty()) return@post activity.standDown()
                val plan = StandbyPlanner.plan(servers, Instant.now()) { reset }
                StandbyAutomation.arm(activity, plan, profiles.associate { it.storageId to it.packageName })
                activity.onScheduledArmed()
            }
        }
    }

    private fun StandbyActivity.isGone(): Boolean = isFinishing || isDestroyed

    /** The chosen servers that still exist, one per client. */
    private fun selectedServers(context: Context, profiles: List<StandbyProfile>, keys: Set<String>): List<StandbyServer> =
        StandbyPlanner.servers(profiles).filter { it.key in keys }.distinctBy(StandbyServer::packageName)

    /** Screen on or a call in progress: someone is using the phone. */
    private fun isInUse(context: Context): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        return interactive || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun record(context: Context, keys: Set<String>, outcome: StandbyOutcome) {
        val now = System.currentTimeMillis()
        StandbyResults.save(context, keys.map { key ->
            val parts = key.split('|')
            StandbyResult(parts.first(), GameServerRegion.fromStored(parts.getOrNull(1)), outcome, now)
        })
    }

    private fun setAlarm(context: Context, start: Instant, reset: Instant, keys: List<String>) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val fire = fireIntent(context, reset, keys)
        if (canScheduleExactAlarms(context)) {
            // An alarm clock is exact even in Doze and lets the screen come on at its time.
            val show = PendingIntent.getActivity(
                context,
                REQUEST_SHOW,
                Intent(context, OptionsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(start.toEpochMilli(), show), fire)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start.toEpochMilli(), fire)
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(fireIntent(context, null, emptyList()))
    }

    private fun fireIntent(context: Context, reset: Instant?, keys: List<String>): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_FIRE,
        Intent(context, ScheduledRecordingAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .apply {
                if (reset != null) putExtra(EXTRA_RESET, reset.toEpochMilli())
                putExtra(EXTRA_SERVERS, keys.toTypedArray())
            },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun handledReset(context: Context): Instant? =
        prefs(context).getLong(KEY_HANDLED_RESET, -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

    /**
     * A debug build with `files/scheduled_rehearsal` holding a number of minutes schedules the
     * next wake-up with every chosen server resetting that far from now, once; the file is then
     * removed so later days follow the real resets.
     */
    private fun takeRehearsal(context: Context): Duration? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val file = File(context.filesDir, REHEARSAL_FILE)
        val minutes = runCatching { file.readText().trim().toLong() }.getOrNull() ?: return null
        file.delete()
        return Duration.ofMinutes(minutes)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "GF2Scheduled"
    private const val PREFS = "scheduled_recording"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVERS = "servers"
    private const val KEY_HANDLED_RESET = "handled_reset"
    private const val KEY_NEXT_START = "next_start"
    private const val KEY_NEXT_REGIONS = "next_regions"
    private const val REQUEST_FIRE = 4_505
    private const val REQUEST_SHOW = 4_506
    private const val REHEARSAL_FILE = "scheduled_rehearsal"

    /** A run needs at least this long before its grace period ends to be worth waking for. */
    private val LATEST_START_MARGIN: Duration = Duration.ofMinutes(1)
}
