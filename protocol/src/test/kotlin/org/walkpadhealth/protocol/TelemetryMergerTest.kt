package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelemetryMergerTest {
    private val running = Telemetry(BeltStatus.RUNNING, null, kcal = 3.6, elapsedSec = 63)

    @Test fun beforeAnyFtmsFrameTheTelemetryIsUnchanged() {
        assertEquals(running, TelemetryMerger().merge(running))
    }

    @Test fun ftmsSuppliesSpeedAndDistanceWhileFff1KeepsStatusAndKcal() {
        val m = TelemetryMerger()
        m.onFtms(FtmsReading(speedKmh = 6.43, distanceM = 93.0, kcal = 4.0, elapsedSec = 76))
        val t = m.merge(running)
        assertEquals(BeltStatus.RUNNING, t.status)
        assertEquals(6.43, t.speedKmh!!, 1e-9); assertEquals(93.0, t.distanceM!!, 1e-9)
        assertEquals(3.6, t.kcal!!, 1e-9)         // fff1 has tenths of a kcal, FTMS only whole ones
        assertEquals(63, t.elapsedSec)
    }

    @Test fun ftmsFillsKcalAndElapsedWhenFff1HasNone() {
        val m = TelemetryMerger()
        m.onFtms(FtmsReading(1.0, 5.0, 2.0, 30))
        val t = m.merge(Telemetry(BeltStatus.IDLE, null))
        assertEquals(2.0, t.kcal!!, 1e-9); assertEquals(30, t.elapsedSec)
    }

    @Test fun resetForgetsTheLastFtmsFrame() {
        val m = TelemetryMerger()
        m.onFtms(FtmsReading(6.43, 93.0, 4.0, 76)); m.reset()
        assertNull(m.merge(running).speedKmh)
    }

    @Test fun aWalkReplayedThroughMergerAndTrackerKeepsThePadDistance() {
        val m = TelemetryMerger(); val tr = SessionTracker()
        for (s in 0..20) {
            m.onFtms(FtmsReading(speedKmh = 3.2, distanceM = s * 1.5, kcal = 0.0, elapsedSec = s))
            tr.onTelemetry(m.merge(Telemetry(BeltStatus.RUNNING, null, kcal = s * 0.1, elapsedSec = s)), s * 1000L)
        }
        val out = tr.onTelemetry(Telemetry(BeltStatus.STOPPED, null), 21_000)!!
        assertEquals(30.0, out.deviceDistanceM!!, 1e-9)          // pad distance, start baseline 0
        assertEquals(2.0, out.deviceKcal!!, 1e-9)
        assertEquals(20L, out.activeSec)
    }
}
