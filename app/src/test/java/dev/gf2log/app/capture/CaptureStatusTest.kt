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
        assertTrue(CaptureStatus.isStarting)
        assertEquals(CaptureNotice.PREPARING, CaptureStatus.snapshot().notice?.notice)

        CaptureStatus.markRunning(listOf("pkg.one", "pkg.two"))
        CaptureStatus.report(CaptureNotice.ROSTER_SAVED, "12", "Owls")
        val running = CaptureStatus.snapshot()
        assertTrue(CaptureStatus.isRunning)
        assertFalse(CaptureStatus.isStarting)
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
