package org.underfoot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepGateTest {
    private val MIN = 60_000L
    private fun gate(m: Int = 10) = SleepGate(m)

    @Test fun neverReleasesWhileTheBeltRuns() {
        val g = gate()
        for (s in 0..(30 * 60)) assertFalse(g.onTick(true, BeltStatus.RUNNING, 1_000L + s * 1000L))
    }

    @Test fun releasesAfterTheConfiguredTimePaused() {
        val g = gate(10)
        assertFalse(g.onTick(true, BeltStatus.PAUSED, 1_000))
        assertFalse(g.onTick(true, BeltStatus.PAUSED, 1_000 + 10 * MIN - 1))
        assertTrue(g.onTick(true, BeltStatus.PAUSED, 1_000 + 10 * MIN))
    }

    @Test fun idleCountsAsQuietToo() {
        val g = gate(5)
        g.onTick(true, BeltStatus.IDLE, 1_000)
        assertTrue(g.onTick(true, BeltStatus.IDLE, 1_000 + 5 * MIN))
    }

    @Test fun runningResetsTheTimer() {
        val g = gate(10)
        g.onTick(true, BeltStatus.PAUSED, 1_000)
        g.onTick(true, BeltStatus.RUNNING, 1_000 + 9 * MIN)
        g.onTick(true, BeltStatus.PAUSED, 1_000 + 9 * MIN + 1000)
        assertFalse(g.onTick(true, BeltStatus.PAUSED, 1_000 + 10 * MIN + 2000))
    }

    @Test fun zeroMeansOff() {
        val g = gate(0)
        g.onTick(true, BeltStatus.PAUSED, 1_000)
        assertFalse(g.onTick(true, BeltStatus.PAUSED, 1_000 + 600 * MIN))
    }

    @Test fun notConnectedNeverCounts() {
        val g = gate(5)
        g.onTick(false, BeltStatus.IDLE, 1_000)
        assertFalse(g.onTick(false, BeltStatus.IDLE, 1_000 + 60 * MIN))
    }

    @Test fun holdoffBlocksReconnectUntilItExpiresAndCancelEndsItEarly() {
        val g = gate(5)
        g.onTick(true, BeltStatus.PAUSED, 1_000)
        val at = 1_000 + 5 * MIN
        assertTrue(g.onTick(true, BeltStatus.PAUSED, at))
        assertEquals(15 * MIN, g.waitMs(at))
        assertEquals(10 * MIN, g.waitMs(at + 5 * MIN))
        assertEquals(0L, g.waitMs(at + 15 * MIN))
        g.cancel()
        assertEquals(0L, g.waitMs(at))
    }
}
