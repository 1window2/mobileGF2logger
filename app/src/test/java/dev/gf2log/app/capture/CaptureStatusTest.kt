package dev.gf2log.app.capture

import dev.gf2log.protocol.Gfl2PayloadDecoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureStatusTest {
    @Before
    fun setUp() {
        MainThread.testExecutor = { it() }
        CaptureStatus.markStopped()
        // A stop without a reason keeps an earlier test's stop notice.
        CaptureStatus.clearIdleNotice()
        CaptureStatus.beginSession(guided = false)
    }

    @After
    fun tearDown() {
        MainThread.testExecutor = null
    }

    @Test
    fun tracksPhaseNoticeAndTargetsAsOneSnapshot() {
        assertEquals(CapturePhase.STOPPED, CaptureStatus.snapshot().phase)
        assertNull(CaptureStatus.snapshot().notice)

        CaptureStatus.markStarting()
        assertEquals(CapturePhase.STARTING, CaptureStatus.snapshot().phase)
        assertEquals(CaptureNotice.PREPARING, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.markRunning(listOf("pkg.one", "pkg.two"))
        CaptureStatus.report(CaptureNotice.ROSTER_SAVED, "12", "Owls")
        val running = CaptureStatus.snapshot()
        assertTrue(CaptureStatus.isRunning)
        assertEquals(listOf("pkg.one", "pkg.two"), running.capturedTargets)
        assertEquals(CaptureNotice.ROSTER_SAVED, running.notice?.notice)
        assertEquals(listOf("12", "Owls"), running.notice?.args)

        CaptureStatus.markStopped(CaptureNotice.NATIVE_START_FAILED)
        val stopped = CaptureStatus.snapshot()
        assertEquals(CapturePhase.STOPPED, stopped.phase)
        assertEquals(emptyList<String>(), stopped.capturedTargets)
        assertEquals(CaptureNotice.NATIVE_START_FAILED, stopped.notice?.notice)
    }

    @Test
    fun exposesImmutableGuidedCaptureProgressUntilTheNextSession() {
        CaptureStatus.beginSession(guided = true)
        CaptureStatus.markUsefulPayload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS)
        val progress = requireNotNull(CaptureStatus.snapshot().guided)
        assertEquals(
            setOf(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS),
            progress.capturedPayloadTypes,
        )

        CaptureStatus.markStopped(CaptureNotice.CAPTURE_ONCE_COMPLETE)
        assertEquals(progress, CaptureStatus.snapshot().guided)

        CaptureStatus.beginSession(guided = false)
        assertNull(CaptureStatus.snapshot().guided)
    }

    @Test
    fun concurrentPayloadMarksAreNeverLost() {
        CaptureStatus.beginSession(guided = true)
        val types = (1..64).toList()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(types.size)
        types.forEach { type ->
            pool.execute {
                CaptureStatus.markUsefulPayload(type)
                CaptureStatus.report(CaptureNotice.TRAFFIC, "$type", "0", "0", "0", "0")
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(types.toSet(), CaptureStatus.snapshot().guided?.capturedPayloadTypes)
    }

    @Test
    fun stoppedIsPublishedOnlyAfterTeardownAndKeepsTheStopNotice() {
        CaptureStatus.markRunning(listOf("pkg"))

        CaptureStatus.markStopping(CaptureNotice.STOPPED_UNEXPECTEDLY)
        val stopping = CaptureStatus.snapshot()
        assertEquals(CapturePhase.STOPPING, stopping.phase)
        assertEquals(emptyList<String>(), stopping.capturedTargets)
        assertEquals(CaptureNotice.STOPPED_UNEXPECTEDLY, stopping.notice?.notice)

        CaptureStatus.markTornDown()
        val stopped = CaptureStatus.snapshot()
        assertEquals(CapturePhase.STOPPED, stopped.phase)
        assertEquals(CaptureNotice.STOPPED_UNEXPECTEDLY, stopped.notice?.notice)
    }

    @Test
    fun trafficNeverHidesAWarningOrErrorAndAppearsOnlyWhileRunning() {
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.report(CaptureNotice.TRAFFIC, "64", "0", "1", "0", "0")
        assertEquals(CaptureNotice.TRAFFIC, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.report(CaptureNotice.ROSTER_SAVE_FAILED)
        CaptureStatus.report(CaptureNotice.TRAFFIC, "128", "0", "1", "0", "0")
        assertEquals(CaptureNotice.ROSTER_SAVE_FAILED, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.report(CaptureNotice.PARSER_OVERLOADED, "1")
        CaptureStatus.report(CaptureNotice.TRAFFIC, "192", "0", "1", "0", "1")
        assertEquals(CaptureNotice.PARSER_OVERLOADED, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.markStopping(notice = null)
        CaptureStatus.report(CaptureNotice.TRAFFIC, "256", "0", "1", "0", "1")
        assertNull(CaptureStatus.snapshot().notice)

        CaptureStatus.markTornDown()
        CaptureStatus.report(CaptureNotice.TRAFFIC, "320", "0", "1", "0", "1")
        assertNull(CaptureStatus.snapshot().notice)
    }

    @Test
    fun aDamagedDatabaseStaysShownWhileRunningUntilAnotherErrorReplacesIt() {
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.report(CaptureNotice.DATABASE_DAMAGED, "Alpha")
        CaptureStatus.report(CaptureNotice.ROSTER_SAVED, "3", "Bravo")
        CaptureStatus.report(CaptureNotice.PLATOON_DETECTED, "Bravo", "2", "HaoPlay")
        CaptureStatus.report(CaptureNotice.PARSER_OVERLOADED, "1")
        CaptureStatus.report(CaptureNotice.TRAFFIC, "64", "0", "1", "0", "0")
        assertEquals(CaptureNotice.DATABASE_DAMAGED, CaptureStatus.snapshot().notice?.notice)
        assertEquals(listOf("Alpha"), CaptureStatus.snapshot().notice?.args)

        CaptureStatus.report(CaptureNotice.UPDATES_SAVE_FAILED)
        assertEquals(CaptureNotice.UPDATES_SAVE_FAILED, CaptureStatus.snapshot().notice?.notice)

        // Once stopped, an admission decision reports its outcome as usual.
        CaptureStatus.markStopping(CaptureNotice.DATABASE_DAMAGED, "Alpha")
        CaptureStatus.markTornDown()
        CaptureStatus.report(CaptureNotice.PENDING_DISCARDED)
        assertEquals(CaptureNotice.PENDING_DISCARDED, CaptureStatus.snapshot().notice?.notice)
    }

    @Test
    fun workDrainedDuringTeardownKeepsTheStopReasonUnlessItIsWorse() {
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(CaptureNotice.CAPTURE_ONCE_COMPLETE)
        CaptureStatus.report(CaptureNotice.ROSTER_SAVED, "3", "P")
        CaptureStatus.report(CaptureNotice.TRAFFIC, "64", "0", "1", "0", "0")
        CaptureStatus.markTornDown()
        assertEquals(CaptureNotice.CAPTURE_ONCE_COMPLETE, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(CaptureNotice.STOPPED_UNEXPECTEDLY)
        CaptureStatus.report(CaptureNotice.PLATOON_DETECTED, "P", "1", "HaoPlay")
        CaptureStatus.report(CaptureNotice.FLOW_BUFFER_OVERFLOW)
        assertEquals(CaptureNotice.STOPPED_UNEXPECTEDLY, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(CaptureNotice.CAPTURE_ONCE_COMPLETE)
        CaptureStatus.report(CaptureNotice.ROSTER_SAVE_FAILED)
        assertEquals(CaptureNotice.ROSTER_SAVE_FAILED, CaptureStatus.snapshot().notice?.notice)

        // Once stopped, an admission decision reports its outcome as before.
        CaptureStatus.markTornDown()
        CaptureStatus.report(CaptureNotice.PENDING_DISCARDED)
        assertEquals(CaptureNotice.PENDING_DISCARDED, CaptureStatus.snapshot().notice?.notice)
    }

    @Test
    fun aSecondStopWithoutAReasonKeepsTheFirstStopsReason() {
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(CaptureNotice.STOPPED_UNEXPECTEDLY)
        CaptureStatus.markStopping(notice = null)
        CaptureStatus.markTornDown()
        assertEquals(CaptureNotice.STOPPED_UNEXPECTEDLY, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.markStopped()
        assertEquals(CaptureNotice.STOPPED_UNEXPECTEDLY, CaptureStatus.snapshot().notice?.notice)

        // A stop that does give a reason replaces it.
        CaptureStatus.markStopped(CaptureNotice.NATIVE_START_FAILED)
        assertEquals(CaptureNotice.NATIVE_START_FAILED, CaptureStatus.snapshot().notice?.notice)

        // Stopping a running capture without a reason still clears its progress notice.
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(notice = null)
        assertNull(CaptureStatus.snapshot().notice)
    }

    @Test
    fun aRevokedVpnStaysRecognizableThroughTheStopThatFollows() {
        CaptureStatus.markRunning(listOf("pkg"))
        assertFalse(CaptureStatus.snapshot().endedByVpnRevoke)

        CaptureStatus.markStopping(CaptureNotice.VPN_PERMISSION_REVOKED)
        assertTrue(CaptureStatus.snapshot().endedByVpnRevoke)

        // Standby's own stop request arrives while the revoked capture tears down.
        CaptureStatus.markStopping(notice = null)
        CaptureStatus.markTornDown()
        CaptureStatus.markStopped()
        assertTrue(CaptureStatus.snapshot().endedByVpnRevoke)

        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(CaptureNotice.STOPPED_UNEXPECTEDLY)
        assertFalse(CaptureStatus.snapshot().endedByVpnRevoke)
    }

    @Test
    fun theGuidedChecklistShowsOnlyWhileCapturingOrBesideItsOutcome() {
        val guided = CaptureStatus.GuidedProgress(setOf(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS))
        fun snapshot(phase: CapturePhase, notice: CaptureNotice?) = CaptureSnapshot(
            phase = phase,
            notice = notice?.let { CaptureNoticeEvent(it, atMillis = 0) },
            capturedTargets = emptyList(),
            guided = guided,
        )

        assertEquals(guided, snapshot(CapturePhase.STARTING, CaptureNotice.PREPARING).visibleGuided)
        assertEquals(guided, snapshot(CapturePhase.RUNNING, CaptureNotice.TRAFFIC).visibleGuided)
        assertEquals(guided, snapshot(CapturePhase.STOPPED, CaptureNotice.CAPTURE_ONCE_PARTIAL).visibleGuided)
        assertEquals(guided, snapshot(CapturePhase.STOPPING, CaptureNotice.CAPTURE_ONCE_COMPLETE).visibleGuided)
        assertNull(snapshot(CapturePhase.STOPPED, notice = null).visibleGuided)
        assertNull(snapshot(CapturePhase.STOPPED, CaptureNotice.NATIVE_START_FAILED).visibleGuided)
        assertNull(snapshot(CapturePhase.STOPPING, CaptureNotice.VPN_PERMISSION_REVOKED).visibleGuided)
        assertNull(snapshot(CapturePhase.RUNNING, CaptureNotice.TRAFFIC).copy(guided = null).visibleGuided)
    }

    @Test
    fun aPartialCaptureNamesItsMissingItemsWithTheChecklistLabels() {
        val labels = mapOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY to "Platoon activity",
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES to "Membership updates",
        )
        val partial = CaptureNoticeEvent(
            CaptureNotice.CAPTURE_ONCE_PARTIAL,
            listOf(
                Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY.toString(),
                Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES.toString(),
            ),
            atMillis = 0,
        )

        assertEquals(listOf("Platoon activity, Membership updates"), partial.displayArgs(labels::get))
        // Every other notice shows its args unchanged.
        val saved = CaptureNoticeEvent(CaptureNotice.ROSTER_SAVED, listOf("21935", "P"), atMillis = 0)
        assertEquals(listOf("21935", "P"), saved.displayArgs(labels::get))
    }

    @Test
    fun everyCheckedPayloadTypeHasALabel() {
        assertEquals(CaptureRunTracker.REQUIRED_TYPES, CaptureRunTracker.CHECKLIST_LABELS.keys)
    }

    @Test
    fun eachWarningOrErrorIsAnnouncedOnceAndRoutineProgressNever() {
        val failed = CaptureNoticeEvent(CaptureNotice.ROSTER_SAVE_FAILED, atMillis = 1)
        val traffic = CaptureNoticeEvent(CaptureNotice.TRAFFIC, listOf("1", "0", "0", "0", "0"), atMillis = 2)

        assertTrue(failed.isNewAlert(lastAnnounced = null))
        assertFalse(failed.isNewAlert(lastAnnounced = failed))
        assertFalse(traffic.isNewAlert(lastAnnounced = failed))
        assertFalse((null as CaptureNoticeEvent?).isNewAlert(lastAnnounced = failed))
        // The same failure reported again is new.
        assertTrue(failed.copy(atMillis = 3).isNewAlert(lastAnnounced = failed))
        assertTrue(
            CaptureNoticeEvent(CaptureNotice.PARSER_OVERLOADED, listOf("1"), atMillis = 4)
                .isNewAlert(lastAnnounced = failed),
        )
    }

    @Test
    fun teardownLeavesAPhaseANewerStartAlreadyOwns() {
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(notice = null)
        // A start that arrives during teardown restarts once it finishes.
        CaptureStatus.markStarting()

        CaptureStatus.markTornDown()

        assertEquals(CapturePhase.STARTING, CaptureStatus.snapshot().phase)
    }

    @Test
    fun everyRunIsCountedSoAStaleRunningPhaseIsDistinguishable() {
        val before = CaptureStatus.snapshot().runCount
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(notice = null)
        CaptureStatus.markTornDown()
        CaptureStatus.markRunning(listOf("pkg"))

        assertEquals(before + 2, CaptureStatus.snapshot().runCount)
    }

    @Test
    fun clearingAnIdleNoticeNeverEndsAnActiveCapture() {
        CaptureStatus.markStopped(CaptureNotice.NATIVE_START_FAILED)
        CaptureStatus.clearIdleNotice()
        assertNull(CaptureStatus.snapshot().notice)

        CaptureStatus.markStarting()
        CaptureStatus.clearIdleNotice()
        assertEquals(CapturePhase.STARTING, CaptureStatus.snapshot().phase)
    }

    @Test
    fun whenStoppedRunsImmediatelyWhileStopped() {
        var runs = 0
        CaptureStatus.whenStopped { runs++ }

        assertEquals(1, runs)
        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopped()
        assertEquals(1, runs)
    }

    @Test
    fun whenStoppedWaitsThroughTheWholeTeardownAndRunsOnce() {
        CaptureStatus.markRunning(listOf("pkg"))
        var runs = 0
        CaptureStatus.whenStopped { runs++ }

        CaptureStatus.markStopping(notice = null)
        CaptureStatus.report(CaptureNotice.TRAFFIC, "1", "0", "0", "0", "0")
        assertEquals(0, runs)

        CaptureStatus.markTornDown()
        assertEquals(1, runs)

        CaptureStatus.markRunning(listOf("pkg"))
        CaptureStatus.markStopping(notice = null)
        CaptureStatus.markTornDown()
        assertEquals(1, runs)
    }

    @Test
    fun aCancelledStopWaitNeverRuns() {
        CaptureStatus.markRunning(listOf("pkg"))
        var runs = 0
        val cancel = CaptureStatus.whenStopped { runs++ }

        cancel()
        CaptureStatus.markStopping(notice = null)
        CaptureStatus.markTornDown()

        assertEquals(0, runs)
    }

    @Test
    fun listenersReceiveTheCurrentSnapshotImmediatelyAndAfterChanges() {
        val received = mutableListOf<CaptureSnapshot>()
        val listener: (CaptureSnapshot) -> Unit = { received += it }

        CaptureStatus.addListener(listener)
        assertEquals(CapturePhase.STOPPED, received.single().phase)

        CaptureStatus.markRunning(listOf("pkg"))
        assertEquals(CapturePhase.RUNNING, received.last().phase)

        CaptureStatus.removeListener(listener)
        CaptureStatus.markStopped()
        assertEquals(2, received.size)
    }
}
