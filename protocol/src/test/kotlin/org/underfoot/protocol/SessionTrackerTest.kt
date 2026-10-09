package org.underfoot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTrackerTest {
    private fun run(sp: Double? = 3.0) = Telemetry(BeltStatus.RUNNING, sp)
    private fun st(s: BeltStatus) = Telemetry(s, null)
    private fun SessionTracker.runFor(fromSec: Int, toSec: Int, sp: Double? = 3.0) {
        for (s in fromSec..toSec) onTelemetry(run(sp), s * 1000L)
    }

    @Test fun recordsARunningSession() {
        val t = SessionTracker()
        t.runFor(0, 60)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 61_000)!!
        assertEquals(0L, out.startMs)
        assertEquals(61_000L, out.endMs)
        assertEquals(60L, out.activeSec)
        assertEquals(50.0, out.integratedDistanceM, 1e-6)   // 3 km/h for 60 s
        assertFalse(t.isActive)
    }

    @Test fun aShortDisconnectIsNotCountedAsWalking() {
        val t = SessionTracker()
        t.runFor(0, 20)
        t.onDisconnect(20_000)
        t.onTelemetry(run(), 24_000)                 // back after 4 s: those 4 s were not observed
        t.runFor(25, 30)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 31_000)!!
        assertEquals(26L, out.activeSec)             // 20 s before, 6 s after the return (24 -> 30); the 4 s gap is not counted
        assertEquals(26 * 3.0 / 3.6, out.integratedDistanceM, 1e-6)
    }

    @Test fun idleFramesWithoutASessionDoNothing() {
        val t = SessionTracker()
        assertNull(t.onTelemetry(st(BeltStatus.IDLE), 0))
        assertNull(t.onTelemetry(st(BeltStatus.STOPPED), 1000))
        assertFalse(t.isActive)
    }

    @Test fun runningWithoutSpeedCountsTimeButNoDistance() {
        val t = SessionTracker()
        t.runFor(0, 20, sp = null)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertEquals(20L, out.activeSec)
        assertEquals(0.0, out.integratedDistanceM)
    }

    @Test fun pauseDoesNotAccrue() {
        val t = SessionTracker()
        t.runFor(0, 10)
        for (s in 11..20) t.onTelemetry(st(BeltStatus.PAUSED), s * 1000L)
        t.runFor(21, 30)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 31_000)!!
        assertEquals(19L, out.activeSec)
        assertEquals(19 * 3.0 / 3.6, out.integratedDistanceM, 1e-6)
    }

    @Test fun pauseOfAMinuteEndsTheWalkWhereThePauseBegan() {
        val t = SessionTracker(pauseEndMs = 60_000)
        t.runFor(0, 30)
        for (s in 31..90) assertNull(t.onTelemetry(Telemetry(BeltStatus.PAUSED, null), s * 1000L))
        val out = t.onTelemetry(Telemetry(BeltStatus.PAUSED, null), 91_000)!!
        assertEquals(31_000L, out.endMs); assertEquals(30L, out.activeSec)
        assertFalse(t.isActive)
        assertNull(t.onTelemetry(Telemetry(BeltStatus.PAUSED, null), 92_000))     // later paused frames start nothing
    }

    @Test fun theTickerEndsAPausedWalkEvenIfNoFramesArrive() {
        val t = SessionTracker(pauseEndMs = 60_000)
        t.runFor(0, 30)
        t.onTelemetry(Telemetry(BeltStatus.PAUSING, null), 31_000)
        assertNull(t.tick(90_999))
        assertEquals(31_000L, t.tick(91_000)!!.endMs)
    }

    @Test fun aShortPauseKeepsTheSameWalk() {
        val t = SessionTracker()
        t.runFor(0, 30)
        for (s in 31..60) t.onTelemetry(Telemetry(BeltStatus.PAUSED, null), s * 1000L)
        t.runFor(61, 80)
        assertNull(t.tick(200_000))                                                // pause timer was cleared by RUNNING
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 81_000)!!
        assertEquals(30L + 19L, out.activeSec)
    }

    @Test fun aFiveMinutePauseKeepsTheSameWalkByDefault() {
        // Pause on the remote, resume from the app minutes later: the pad keeps its counters, so the walk must continue.
        val t = SessionTracker()
        t.runFor(0, 30)
        for (s in 31..330) assertNull(t.onTelemetry(Telemetry(BeltStatus.PAUSED, null), s * 1000L))
        t.onTelemetry(Telemetry(BeltStatus.STARTING, null), 331_000)
        t.runFor(334, 360)
        assertTrue(t.isActive)
        assertEquals(30L + 26L, t.onTelemetry(st(BeltStatus.STOPPED), 361_000)!!.activeSec)
    }

    @Test fun padStepsAreReportedLiveAndAsTheChangeSinceTheStart() {
        val t = SessionTracker()
        t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, steps = 100), 0)
        for (s in 1..20) t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, steps = 100 + s * 2), s * 1000L)
        assertEquals(40, t.progress().steps)
        assertEquals(40, t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!.deviceSteps)
    }

    @Test fun theCountdownAfterAPauseAlsoClearsThePauseTimer() {
        val t = SessionTracker()
        t.runFor(0, 30)
        t.onTelemetry(Telemetry(BeltStatus.PAUSED, null), 31_000)
        t.onTelemetry(Telemetry(BeltStatus.STARTING, null), 80_000)
        assertNull(t.tick(200_000)); assertTrue(t.isActive)
    }

    @Test fun gapOverFiveSecondsIsNotIntegrated() {
        val t = SessionTracker()
        t.onTelemetry(run(), 0); t.onTelemetry(run(), 1_000)
        t.onTelemetry(run(), 20_000)                      // 19 s gap: skipped
        t.onTelemetry(run(), 21_000)
        t.runFor(22, 30)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 31_000)!!
        assertEquals(1L + 1L + 9L, out.activeSec)
    }

    @Test fun shortAccidentalRunIsDiscarded() {
        val t = SessionTracker()
        t.runFor(0, 5)
        assertNull(t.onTelemetry(st(BeltStatus.STOPPED), 6_000))
        assertFalse(t.isActive)
    }

    @Test fun disconnectOfSixtySecondsEndsTheSessionAtDisconnectTime() {
        val t = SessionTracker()
        t.runFor(0, 30)
        t.onDisconnect(31_000)
        assertNull(t.tick(90_999))
        val out = t.tick(91_000)!!
        assertEquals(31_000L, out.endMs)
        assertEquals(30L, out.activeSec)
        assertFalse(t.isActive)
    }

    @Test fun reconnectWithinSixtySecondsResumesTheSameSession() {
        val t = SessionTracker()
        t.runFor(0, 30)
        t.onDisconnect(31_000)
        t.onTelemetry(run(), 50_000)                      // reconnect: gap not integrated
        t.runFor(51, 60)
        assertNull(t.tick(500_000))                       // no disconnect pending any more
        assertTrue(t.isActive)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 61_000)!!
        assertEquals(40L, out.activeSec)
    }

    @Test fun deviceTotalsAreSessionDeltasFromTheStartFrame() {
        val t = SessionTracker()
        t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 100.0, steps = 200, kcal = 10.0), 0)
        for (s in 1..20) t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 100.0 + s * 1.5, steps = 200 + s * 2, kcal = 10.0 + s), s * 1000L)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertEquals(30.0, out.deviceDistanceM)
        assertEquals(40, out.deviceSteps)
        assertEquals(20.0, out.deviceKcal)
    }

    @Test fun counterThatWentDownIsTreatedAsReset() {
        val t = SessionTracker()
        t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 100.0), 0)
        for (s in 1..20) t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 5.0), s * 1000L)
        assertEquals(5.0, t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!.deviceDistanceM)
    }

    @Test fun nonFiniteDeviceValuesAreDropped() {
        val t = SessionTracker()
        t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 0.0), 0)
        for (s in 1..20) t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = Double.POSITIVE_INFINITY, kcal = Double.NaN), s * 1000L)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertNull(out.deviceDistanceM); assertNull(out.deviceKcal)
    }

    @Test fun distanceUsesThePreviousSpeedOverEachInterval() {
        val t = SessionTracker(minActiveSec = 1)
        t.onTelemetry(run(2.0), 0); t.onTelemetry(run(4.0), 1_000); t.onTelemetry(run(4.0), 2_000)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 3_000)!!
        assertEquals((2.0 + 4.0) / 3.6, out.integratedDistanceM, 1e-9)
    }

    @Test fun frameAtExactlySixtySecondsAfterDisconnectEndsOldSessionAndStartsANewOne() {
        val t = SessionTracker()
        t.runFor(0, 30)
        t.onDisconnect(31_000)
        val old = t.onTelemetry(run(), 91_000)!!
        assertEquals(31_000L, old.endMs); assertEquals(30L, old.activeSec)
        assertTrue(t.isActive)                           // the new walk is its own session
        assertEquals(Progress(0, 0.0), t.progress())
    }

    @Test fun summaryCarriesTheWallClockStartCapturedAtBegin() {
        var wall = 1_700_000_000_000L
        val t = SessionTracker(wallClock = { wall })
        t.onTelemetry(run(), 0)
        wall += 999_999                                   // wall clock jumps mid-walk; the stored start must not move
        for (s in 1..20) t.onTelemetry(run(), s * 1000L)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertEquals(1_700_000_000_000L, out.wallStartMs)
        assertEquals(21_000L, out.endMs - out.startMs)
    }

    @Test fun progressTracksTheOpenSession() {
        val t = SessionTracker()
        assertEquals(Progress(0, 0.0), t.progress())
        t.runFor(0, 12)
        assertEquals(12L, t.progress().activeSec)
    }

    @Test fun finishEndsAnOpenSessionAndIsNullOtherwise() {
        val t = SessionTracker()
        assertNull(t.finish(0))
        t.runFor(0, 30)
        assertNotNull(t.finish(31_000))
        assertFalse(t.isActive)
    }

    @Test fun tickWithoutDisconnectIsANoOp() {
        val t = SessionTracker()
        t.runFor(0, 30)
        assertNull(t.tick(1_000_000))
        assertTrue(t.isActive)
    }

    private fun rt(s: BeltStatus, el: Int? = null, d: Double? = null) = Telemetry(s, 3.0, d, null, null, el)
    private val MIN = 60_000L

    @Test fun releasingThePadForSleepKeepsAPausedWalkOpen() {
        val t = SessionTracker()
        t.runFor(0, 30)
        for (s in 31..40) t.onTelemetry(st(BeltStatus.PAUSED), s * 1000L)
        t.onRelease(41_000, 15 * MIN); t.onDisconnect(41_000)   // the planned disconnect must not end the walk in 60 s
        assertNull(t.tick(41_000 + 10 * MIN))
        assertTrue(t.isActive)
    }

    @Test fun walkResumedOnTheRemoteWhileReleasedIsFilledFromThePadCounters() {
        val t = SessionTracker()
        for (s in 0..60) t.onTelemetry(rt(BeltStatus.RUNNING, el = s, d = s * 0.8), s * 1000L)       // 60 s walked
        for (s in 61..70) t.onTelemetry(rt(BeltStatus.PAUSED, el = 60, d = 48.0), s * 1000L)
        t.onRelease(71_000, 15 * MIN); t.onDisconnect(71_000)
        // 5 min later, after 100 s more walking on the remote, the app is back; FTMS (distance) arrives one frame after fff1
        t.onTelemetry(rt(BeltStatus.RUNNING, el = 160), 371_000)
        t.onTelemetry(rt(BeltStatus.RUNNING, el = 161, d = 128.8), 372_000)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 373_000)!!
        assertEquals(161L, out.activeSec)                       // 60 seen + 100 filled + 1 s seen live; the pad says 161
        assertEquals(131.6, out.integratedDistanceM, 0.1)         // 50 m seen live + 80.8 m filled + 0.83 m live
    }

    @Test fun firstFrameAfterTheGapMayBeTheEnd() {
        val t = SessionTracker()
        for (s in 0..60) t.onTelemetry(rt(BeltStatus.RUNNING, el = s), s * 1000L)
        t.onRelease(61_000, 15 * MIN); t.onDisconnect(61_000)
        val out = t.onTelemetry(rt(BeltStatus.STOPPED, el = 100), 400_000)!!      // walked 40 s more, then End on the remote
        assertEquals(100L, out.activeSec)
    }

    @Test fun pauseOfTwentyMinutesPlusHoldoffStillSurvivesAResumeOnTheRemote() {
        val t = SessionTracker()
        for (s in 0..60) t.onTelemetry(rt(BeltStatus.RUNNING, el = s), s * 1000L)
        for (s in 61..70) t.onTelemetry(rt(BeltStatus.PAUSED, el = 60), s * 1000L)       // paused at 61 s
        val rel = 61_000 + 20 * MIN
        t.onRelease(rel, 15 * MIN); t.onDisconnect(rel)
        assertNull(t.tick(61_000 + 31 * MIN))                       // past the plain 30 min backstop, but released
        assertNull(t.onTelemetry(rt(BeltStatus.RUNNING, el = 60 + 600), 61_000 + 35 * MIN))   // back at 35 min, walked 10 min
        assertTrue(t.isActive)
    }

    @Test fun releasedShortlyAfterPausingStillEndsAtTheUsualBackstop() {
        val t = SessionTracker()
        for (s in 0..60) t.onTelemetry(rt(BeltStatus.RUNNING, el = s), s * 1000L)
        t.onTelemetry(st(BeltStatus.PAUSED), 61_000)
        t.onRelease(61_000 + 5 * MIN, 15 * MIN); t.onDisconnect(61_000 + 5 * MIN)
        assertNull(t.tick(61_000 + 29 * MIN))
        // 5 + 15 + 2 = 22 min deadline is earlier than the 30 min backstop, so 30 min decides
        val out = t.tick(61_000 + 30 * MIN)
        assertNotNull(out); assertEquals(61_000L, out.endMs)
    }

    @Test fun aRealDisconnectAfterTheFirstFrameBackStillTimesOut() {
        val t = SessionTracker()
        for (s in 0..60) t.onTelemetry(rt(BeltStatus.RUNNING, el = s), s * 1000L)
        t.onRelease(61_000, 15 * MIN); t.onDisconnect(61_000)
        t.onTelemetry(rt(BeltStatus.RUNNING, el = 90), 200_000)
        t.onDisconnect(210_000)
        assertNotNull(t.tick(210_000 + 61_000))
    }
}
