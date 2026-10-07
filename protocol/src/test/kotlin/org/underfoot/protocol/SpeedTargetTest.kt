package org.underfoot.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedTargetTest {
    @Test fun frameIsOpcode02WithLittleEndianHundredthsOfKmh() {
        assertContentEquals(byteArrayOf(0x02, 0x40, 0x01), FtmsControl.setSpeedFrame(3.20))    // 320 = 0x0140
        assertContentEquals(byteArrayOf(0x02, 0x84.toByte(), 0x02), FtmsControl.setSpeedFrame(6.44))
    }

    @Test fun snapsToTenthsOfAnMphAndClamps() {
        assertEquals(2.8 * SpeedTarget.MPH_TO_KMH, SpeedTarget.snap(2.83 * SpeedTarget.MPH_TO_KMH, imperial = true), 1e-9)
        assertEquals(SpeedTarget.MAX_KMH, SpeedTarget.snap(99.0, imperial = true), 1e-9)
        assertEquals(SpeedTarget.MIN_KMH, SpeedTarget.snap(0.0, imperial = true), 1e-9)
        assertEquals(SpeedTarget.MIN_KMH, SpeedTarget.snap(Double.NaN, imperial = true), 1e-9)
    }

    @Test fun metricSnapsToTenthsOfAKmh() {
        assertEquals(3.2, SpeedTarget.snap(3.23, imperial = false), 1e-9)
    }

    @Test fun stepMovesOneTenthInTheDisplayedUnitAndStopsAtTheLimits() {
        val v = 2.0 * SpeedTarget.MPH_TO_KMH
        assertEquals(2.1 * SpeedTarget.MPH_TO_KMH, SpeedTarget.step(v, +1, imperial = true), 1e-9)
        assertEquals(1.9 * SpeedTarget.MPH_TO_KMH, SpeedTarget.step(v, -1, imperial = true), 1e-9)
        assertEquals(SpeedTarget.MAX_KMH, SpeedTarget.step(SpeedTarget.MAX_KMH, +1, imperial = true), 1e-9)
        assertEquals(SpeedTarget.MIN_KMH, SpeedTarget.step(SpeedTarget.MIN_KMH, -1, imperial = true), 1e-9)
    }

    @Test fun speedCanOnlyBeSetWhileTheBeltRuns() {
        assertTrue(FtmsControl.canSetSpeed(BeltStatus.RUNNING, connected = true))
        for (s in listOf(BeltStatus.IDLE, BeltStatus.PAUSED, BeltStatus.PAUSING, BeltStatus.STARTING, BeltStatus.STOPPED, BeltStatus.UNKNOWN))
            assertFalse(FtmsControl.canSetSpeed(s, connected = true), "$s")
        assertFalse(FtmsControl.canSetSpeed(BeltStatus.RUNNING, connected = false))
    }

    @Test fun replyOpcodeForSetSpeedIs02() {
        assertEquals(0x02, FtmsControl.parseReply(byteArrayOf(0x80.toByte(), 0x02, 0x01))?.opcode)
    }
}
