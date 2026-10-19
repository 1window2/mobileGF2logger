package dev.gf2log.app.standby

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.gf2log.app.StandbyActivity
import dev.gf2log.app.capture.CaptureNotice
import dev.gf2log.app.capture.CapturePhase
import dev.gf2log.app.capture.CaptureSnapshot
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.capture.RosterEvents
import dev.gf2log.app.management.PlatoonProfileRegistry
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Runs standby mode's planned recordings, one server at a time, on the main thread.
 *
 * Every path ends in [finish] or [cancel], and both always release the screen. [finish] locks the
 * phone; if locking fails, standby closes and the phone's own screen timeout locks it. A watchdog
 * deadline, checked every second, forces [finish] whatever state a run is in, and every screen-on
 * hold also expires at that deadline on its own.
 */
internal object StandbyAutomation {

    private enum class Phase { IDLE, WAITING, STARTING_CAPTURE, IN_GAME, RETURNING }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "GF2Standby") }
    private val ticker = object : Runnable {
        override fun run() {
            runCatching(::tick).onFailure { error ->
                Log.e(TAG, "Standby automation failed; finishing", error)
                finish()
            }
            if (phase != Phase.IDLE) handler.postDelayed(this, TICK_MS)
        }
    }

    private lateinit var appContext: Context
    private var phase = Phase.IDLE
    private var runs: List<StandbyRun> = emptyList()
    private var index = 0
    private var deadlineMillis = 0L
    private var profilePackages: Map<String, String> = emptyMap()
    private val results = mutableListOf<StandbyResult>()

    private var phaseStartedAt = 0L
    private var runTimeoutAt = 0L
    private var nextTapAt = 0L
    private var tapsUntil = 0L
    private var loggedIn = false
    private var foreignSince = 0L
    private var startedCapture = false
    private var previousActive: String? = null
    private var handingOff = false
    private var lockWhenDone = true
    private var openedGame: String? = null
    private var standby = WeakReference<StandbyActivity>(null)

    private val rosterListener: (String) -> Unit = ::onRosterPersisted
    private val captureListener: (CaptureSnapshot) -> Unit = ::onCaptureChanged
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) cancel()
        }
    }

    val isArmed: Boolean get() = phase != Phase.IDLE

    /** The deadline standby must end by, or null when nothing is planned. */
    val deadline: Instant? get() = if (isArmed) Instant.ofEpochMilli(deadlineMillis) else null

    /** The planned runs that have not ended yet, for the standby hint. */
    fun pendingRuns(): List<StandbyRun> = if (isArmed) runs.drop(index) else emptyList()

    /**
     * Arms the plan. [profilePackages] maps every saved Platoon to its client package, so a roster
     * of another server's Platoon is recognised as a wrong login. [lockWhenDone] is false only for
     * debug rehearsals, which leave the phone unlocked.
     */
    fun arm(context: Context, plan: List<StandbyRun>, profilePackages: Map<String, String>, lockWhenDone: Boolean = true) {
        if (isArmed) cancel()
        if (plan.isEmpty()) return
        appContext = context.applicationContext
        runs = plan
        index = 0
        deadlineMillis = requireNotNull(StandbyPlanner.finalDeadline(plan)).toEpochMilli()
        this.profilePackages = profilePackages
        results.clear()
        startedCapture = false
        previousActive = null
        handingOff = false
        openedGame = null
        this.lockWhenDone = lockWhenDone
        enter(Phase.WAITING)
        appContext.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        RosterEvents.addListener(rosterListener)
        CaptureStatus.addListener(captureListener)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** A standby screen became visible; between runs it takes over keeping the screen on. */
    fun onStandbyShown(activity: StandbyActivity) {
        standby = WeakReference(activity)
        if (phase == Phase.WAITING || phase == Phase.RETURNING) {
            StandbyAutomationService.instance?.releaseScreen()
            if (phase == Phase.RETURNING) enter(Phase.WAITING)
        }
    }

    /**
     * Called when the standby screen stops. Returns true when automation itself replaced it with
     * the game; otherwise the user left standby, which cancels everything still planned.
     */
    fun onStandbyStopped(activity: StandbyActivity): Boolean {
        if (standby.get() === activity) standby.clear()
        if (handingOff) {
            handingOff = false
            return true
        }
        cancel()
        return false
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now >= deadlineMillis) {
            finish()
            return
        }
        when (phase) {
            Phase.IDLE -> Unit
            Phase.WAITING -> if (now >= runs[index].startAt.toEpochMilli()) beginRun(now)
            Phase.STARTING_CAPTURE -> when {
                CaptureStatus.snapshot().phase == CapturePhase.RUNNING -> launchGame(now)
                now - phaseStartedAt >= CAPTURE_START_TIMEOUT_MS -> endRun(StandbyOutcome.CAPTURE_FAILED)
            }
            Phase.IN_GAME -> when {
                // Someone switched to another app or Home: they are awake, so stand down
                // without closing anything or locking the phone in their hand.
                userLeftGame(now) -> cancel()
                CaptureStatus.snapshot().phase == CapturePhase.STOPPED -> endRun(StandbyOutcome.CAPTURE_FAILED)
                now >= runTimeoutAt -> endRun(StandbyOutcome.TIMED_OUT)
                // Taps only reach the title screen: they stop at login, and in any case after the
                // window, so nothing inside the game is ever pressed.
                now >= nextTapAt && now < tapsUntil && !loggedIn -> {
                    nextTapAt = now + TAP_INTERVAL_MS
                    val (x, y) = startButton(runs[index].server.packageName)
                    StandbyAutomationService.instance?.tapIfFront(runs[index].server.packageName, x, y)
                }
            }
            // The standby screen did not come back between runs: stop rather than leave the game lit.
            Phase.RETURNING -> if (now - phaseStartedAt >= RETURN_TIMEOUT_MS) finish()
        }
    }

    /** True once something other than the game, or this app, has been in front for a while. */
    private fun userLeftGame(now: Long): Boolean {
        val front = StandbyAutomationService.instance?.frontPackage()
        if (front == null || front == runs[index].server.packageName || front == appContext.packageName) {
            foreignSince = 0L
            return false
        }
        if (foreignSince == 0L) foreignSince = now
        // Brief system windows during launch or login do not count.
        return now - foreignSince >= USER_SWITCH_MS
    }

    private fun beginRun(now: Long) {
        val run = runs[index]
        if (now >= run.deadline.toEpochMilli() - MIN_RUN_MS) return skip(StandbyOutcome.MISSED)
        val service = StandbyAutomationService.instance
        if (service == null) {
            // Without the service nothing can be tapped or locked; closing standby lets the
            // phone's own screen timeout lock it.
            record(StandbyOutcome.NO_ACCESSIBILITY)
            index++
            return finish()
        }
        if (isLocked()) return cancel()
        if (VpnService.prepare(appContext) != null) return skip(StandbyOutcome.NO_VPN_CONSENT)
        if (appContext.packageManager.getLaunchIntentForPackage(run.server.packageName) == null) {
            return skip(StandbyOutcome.NOT_INSTALLED)
        }
        service.holdScreenOn(minOf(run.deadline.toEpochMilli(), deadlineMillis))
        enter(Phase.STARTING_CAPTURE)
        val target = run.server.storageIds.first()
        worker.execute {
            // Selecting the planned Platoon makes its client attribute captures to that server.
            val registry = PlatoonProfileRegistry(appContext)
            val previous = runCatching { registry.active()?.storageId }.getOrNull()
            val selected = runCatching { registry.setActive(target) }.getOrDefault(false)
            handler.post {
                if (phase != Phase.STARTING_CAPTURE || runs.getOrNull(index) !== run) {
                    // The run ended meanwhile, so nothing else will put the user's Platoon back.
                    if (selected && previous != null && previous != target) {
                        worker.execute { runCatching { PlatoonProfileRegistry(appContext).setActive(previous) } }
                    }
                    return@post
                }
                previousActive = previous.takeIf { selected && it != target }
                startCapture()
            }
        }
    }

    private fun startCapture() {
        if (CaptureStatus.snapshot().phase != CapturePhase.STOPPED) return
        val started = runCatching {
            appContext.startForegroundService(
                Intent(appContext, CaptureVpnService::class.java)
                    .setAction(CaptureVpnService.ACTION_START)
                    .putExtra(CaptureVpnService.EXTRA_CAPTURE_ONCE, false),
            )
        }
        if (started.isSuccess) {
            startedCapture = true
        } else {
            Log.w(TAG, "Capture could not start", started.exceptionOrNull())
            endRun(StandbyOutcome.CAPTURE_FAILED)
        }
    }

    private fun launchGame(now: Long) {
        val run = runs[index]
        val launch = appContext.packageManager.getLaunchIntentForPackage(run.server.packageName)
            ?: return endRun(StandbyOutcome.NOT_INSTALLED)
        // A game left running from earlier would skip its login, and the roster comes with login.
        killInBackground(run.server.packageName)
        val opener = standby.get()?.takeUnless { it.isFinishing } ?: StandbyAutomationService.instance
        val launched = runCatching {
            (opener ?: appContext).startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!launched) return endRun(StandbyOutcome.CAPTURE_FAILED)
        openedGame = run.server.packageName
        standby.get()?.let { screen ->
            handingOff = true
            screen.finishForAutomation()
        }
        runTimeoutAt = minOf(now + RUN_TIMEOUT_MS, run.deadline.toEpochMilli())
        nextTapAt = now + FIRST_TAP_DELAY_MS
        tapsUntil = now + TAP_WINDOW_MS
        loggedIn = false
        foreignSince = 0L
        enter(Phase.IN_GAME)
    }

    /** Platoon notices only follow a login, so the title screen is gone once one arrives. */
    private fun onCaptureChanged(snapshot: CaptureSnapshot) {
        val notice = snapshot.notice ?: return
        if (phase == Phase.IN_GAME && notice.notice in LOGIN_NOTICES && notice.atMillis >= phaseStartedAt) {
            loggedIn = true
        }
    }

    private fun onRosterPersisted(storageId: String) {
        if (phase != Phase.IN_GAME) return
        val run = runs[index]
        when {
            storageId in run.server.storageIds -> endRun(StandbyOutcome.RECORDED)
            profilePackages[storageId] == run.server.packageName -> endRun(StandbyOutcome.OTHER_PLATOON)
        }
    }

    /** Records [outcome] for the current run, stops what it started, and moves on. */
    private fun endRun(outcome: StandbyOutcome) {
        record(outcome)
        releaseRun()
        index++
        if (index >= runs.size) return finish()
        // A run that failed before the game opened is still on the standby screen: just wait.
        if (standby.get()?.takeUnless { it.isFinishing } != null) {
            StandbyAutomationService.instance?.releaseScreen()
            enter(Phase.WAITING)
            return
        }
        // Close the game, then back to the standby screen until the next run.
        enter(Phase.RETURNING)
        val service = StandbyAutomationService.instance ?: return finish()
        closeOpenedGame(service) {
            if (phase != Phase.RETURNING) return@closeOpenedGame
            val shown = runCatching {
                service.startActivity(
                    Intent(appContext, StandbyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!shown) finish()
        }
    }

    private fun skip(outcome: StandbyOutcome) {
        record(outcome)
        index++
        if (index >= runs.size) finish() else enter(Phase.WAITING)
    }

    /** Ends standby with the phone locked; also used by the watchdog deadline. */
    private fun finish() {
        if (phase == Phase.IDLE) return
        val games = runs.map { it.server.packageName }.distinct()
        if (phase == Phase.STARTING_CAPTURE || phase == Phase.IN_GAME) {
            record(StandbyOutcome.TIMED_OUT)
            index++
        }
        while (index < runs.size) {
            record(StandbyOutcome.MISSED)
            index++
        }
        teardown()
        val service = StandbyAutomationService.instance
        if (service == null) {
            standby.get()?.finishForAutomation()
            standby.clear()
            return
        }
        standby.get()?.finishForAutomation()
        standby.clear()
        closeOpenedGame(service) {
            service.goHome()
            handler.postDelayed({
                games.forEach(::killInBackground)
                val locked = lockWhenDone && service.lockScreen()
                // Released in either case: locked, or left to the phone's own screen timeout.
                service.releaseScreen()
                if (lockWhenDone && !locked) Log.w(TAG, "Lock action refused; leaving the phone to its screen timeout")
            }, CLOSE_GAME_DELAY_MS)
        }
    }

    /** Dismisses the Recents card of the game this run opened, if any, then runs [then]. */
    private fun closeOpenedGame(service: StandbyAutomationService, then: () -> Unit) {
        val game = openedGame ?: return then()
        openedGame = null
        service.closeFromRecents(game) { closed ->
            Log.i(TAG, "Closing $game from Recents: $closed")
            then()
        }
    }

    /** The user is back (left standby or turned the screen off): stop without locking. */
    fun cancel() {
        if (phase == Phase.IDLE) return
        while (index < runs.size) {
            record(StandbyOutcome.CANCELLED)
            index++
        }
        teardown()
        StandbyAutomationService.instance?.releaseScreen()
    }

    private fun teardown() {
        releaseRun()
        enter(Phase.IDLE)
        handler.removeCallbacks(ticker)
        RosterEvents.removeListener(rosterListener)
        CaptureStatus.removeListener(captureListener)
        runCatching { appContext.unregisterReceiver(screenOffReceiver) }
        handingOff = false
        StandbyResults.save(appContext, results.toList())
    }

    /** Stops the capture this run started and restores the Platoon the user had selected. */
    private fun releaseRun() {
        if (startedCapture) {
            startedCapture = false
            runCatching {
                appContext.startService(
                    Intent(appContext, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP),
                )
            }
        }
        previousActive?.let { previous ->
            previousActive = null
            worker.execute { runCatching { PlatoonProfileRegistry(appContext).setActive(previous) } }
        }
    }

    private fun record(outcome: StandbyOutcome) {
        val run = runs.getOrNull(index) ?: return
        Log.i(TAG, "${run.server.key}: $outcome")
        results += StandbyResult(run.server.packageName, run.server.region, outcome, System.currentTimeMillis())
    }

    private fun enter(next: Phase) {
        Log.i(TAG, "$phase -> $next")
        phase = next
        phaseStartedAt = System.currentTimeMillis()
    }

    private fun isLocked(): Boolean =
        appContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Backup only: some phones and Android 14+ refuse to stop another app this way. */
    private fun killInBackground(packageName: String) {
        runCatching { appContext.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(packageName) }
            .onFailure { Log.w(TAG, "Could not close $packageName", it) }
    }

    /** Where each client's title screen "Start" sits, as fractions of the landscape screen. */
    private fun startButton(@Suppress("UNUSED_PARAMETER") packageName: String): Pair<Float, Float> =
        START_X to START_Y

    private const val TAG = "GF2Standby"
    private const val TICK_MS = 1_000L
    private const val CAPTURE_START_TIMEOUT_MS = 20_000L
    private const val FIRST_TAP_DELAY_MS = 15_000L
    private const val TAP_INTERVAL_MS = 4_000L
    private const val TAP_WINDOW_MS = 2 * 60_000L
    private const val USER_SWITCH_MS = 5_000L
    private const val RUN_TIMEOUT_MS = 4 * 60_000L
    private const val MIN_RUN_MS = 60_000L
    private const val RETURN_TIMEOUT_MS = 8_000L
    private const val CLOSE_GAME_DELAY_MS = 1_500L
    private const val START_X = 0.5f
    private const val START_Y = 0.85f
    private val LOGIN_NOTICES = setOf(
        CaptureNotice.PLATOON_DETECTED,
        CaptureNotice.PENDING_DETECTED,
        CaptureNotice.PLATOON_CONFIRMED,
        CaptureNotice.IDENTITY_CHANGED,
        CaptureNotice.PENDING_IDENTITY_CHANGED,
        CaptureNotice.ROSTER_SAVED,
        CaptureNotice.ROSTER_UPDATED,
        CaptureNotice.ROSTER_RECOVERED,
    )
}
