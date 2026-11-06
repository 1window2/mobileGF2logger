package dev.gf2log.app.capture

import dev.gf2log.protocol.Gfl2PayloadDecoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureRunTrackerTest {
    @Before
    fun setUp() {
        MainThread.testExecutor = { it() }
        CaptureStatus.beginSession(guided = true)
    }

    @After
    fun tearDown() {
        CaptureStatus.beginSession(guided = false)
        MainThread.testExecutor = null
    }

    @Test
    fun oneTimeCaptureCompletesOnlyForTheFirstScope() {
        var completed = 0
        var rosters = 0
        val tracker = CaptureRunTracker(
            captureOnce = true,
            onComplete = { completed += 1 },
            onTargetRosterCaptured = { rosters += 1 },
        )

        tracker.mark("first", Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE)
        assertNull(tracker.missingAfterRoster())
        tracker.rosterCaptured("second")
        tracker.mark("second", Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY)
        tracker.mark("second", Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES)
        assertEquals(0, rosters)

        tracker.rosterCaptured("first")
        assertEquals(1, rosters)
        assertEquals(
            listOf(Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY, Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES),
            tracker.missingAfterRoster(),
        )
        assertEquals(0, completed)

        tracker.mark("first", Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY)
        tracker.mark("first", Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES)
        assertEquals(1, completed)
        assertEquals(emptyList<Int>(), tracker.missingAfterRoster())
        assertEquals(
            CaptureRunTracker.REQUIRED_TYPES,
            CaptureStatus.snapshot().guided?.capturedPayloadTypes,
        )
    }

    @Test
    fun theGracePeriodReportsWhatIsMissingAsPayloadTypes() {
        val tracker = CaptureRunTracker(captureOnce = true)
        assertNull(tracker.graceOutcome())

        tracker.rosterCaptured("first")
        tracker.mark("first", Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY)

        assertEquals(
            CaptureNotice.CAPTURE_ONCE_PARTIAL to listOf(
                Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE.toString(),
                Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES.toString(),
            ),
            tracker.graceOutcome(),
        )
    }

    @Test
    fun aChecklistCompletedAtTheGraceDeadlineEndsAsComplete() {
        val tracker = CaptureRunTracker(captureOnce = true)
        tracker.rosterCaptured("first")
        listOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
        ).forEach { tracker.mark("first", it) }

        // The grace stop can run before the completion it raced; it must not read as partial.
        assertEquals(CaptureNotice.CAPTURE_ONCE_COMPLETE to emptyList<String>(), tracker.graceOutcome())
    }

    @Test
    fun continuousCaptureMarksEveryScopeWithoutStopping() {
        var completed = 0
        val tracker = CaptureRunTracker(captureOnce = false, onComplete = { completed += 1 })

        CaptureRunTracker.REQUIRED_TYPES.forEach { tracker.mark("any", it) }

        assertEquals(0, completed)
        assertTrue(
            CaptureStatus.snapshot().guided?.capturedPayloadTypes.orEmpty()
                .containsAll(CaptureRunTracker.REQUIRED_TYPES),
        )
    }
}
