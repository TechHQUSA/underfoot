package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Frames marked REAL were captured from a URTM059 with nRF Connect on 2026-10-07 (see captures/). */
class UrevoDriverTest {
    private fun hex(s: String) = s.split('-').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun frame(status: Int, size: Int): ByteArray = ByteArray(size).also {
        it[0] = 0x02; it[1] = 0x51; it[2] = status.toByte(); it[size - 1] = 0x03
    }

    // REAL
    private val idle = hex("02-51-00-01-08-03")
    private val countdown = listOf(hex("02-51-02-03-0C-03"), hex("02-51-02-02-0F-03"), hex("02-51-02-01-0E-03"))
    private val ack = hex("02-50-03-00-00-59-F6-03")
    private val runStart = hex("02-51-03-06-00-00-00-00-00-00-00-00-00-00-00-00-00-39-00-6B-F0-5D-50-C1-03")
    private val run63s = hex("02-51-03-28-00-3F-00-04-00-24-00-04-00-00-00-00-00-39-00-6B-F0-5D-50-72-03")
    private val pausing = hex("02-51-04-28-00-4C-00-05-00-2E-00-04-00-00-00-00-00-39-00-6B-F0-5D-50-1B-03")
    private val paused = hex("02-51-0A-00-00-4C-00-05-00-2E-00-08-00-00-00-00-00-39-00-6B-F0-5D-50-79-03")
    private val ftmsSlow = hex("84-04-60-00-00-00-00-00-00-FF-FF-FF-00-00")
    private val ftmsEarly = hex("84-04-01-01-04-00-00-00-00-FF-FF-FF-0B-00")
    private val ftmsFast = hex("84-04-83-02-5D-00-00-04-00-FF-FF-FF-4C-00")

    @Test fun handshakeFramesAreExact() {
        assertContentEquals(byteArrayOf(0x02, 0x51, 0x0b, 0x03), UrevoDriver.handshakeFrames[0])
        assertContentEquals(byteArrayOf(0x02, 0x50, 0x03, 0x09, 0x03), UrevoDriver.handshakeFrames[1])
    }

    @Test fun idlePingDecodes() {
        val t = UrevoDriver.decodeFff1(idle)!!
        assertEquals(BeltStatus.IDLE, t.status)
        assertNull(t.elapsedSec); assertNull(t.kcal); assertNull(t.speedKmh)
    }

    @Test fun countdownFramesAreStarting() {
        for (f in countdown) assertEquals(BeltStatus.STARTING, UrevoDriver.decodeFff1(f)!!.status)
    }

    @Test fun handshakeAckIsNotTelemetry() { assertNull(UrevoDriver.decodeFff1(ack)) }

    @Test fun runningFrameCarriesElapsedAndKcalButNotSpeed() {
        val t = UrevoDriver.decodeFff1(run63s)!!
        assertEquals(BeltStatus.RUNNING, t.status)
        assertEquals(63, t.elapsedSec)
        assertEquals(3.6, t.kcal!!, 1e-9)
        assertNull(t.speedKmh)          // speed rides on FTMS in SI units; fff1's own unit is 0.1 mph
        assertNull(t.distanceM)
    }

    @Test fun firstRunningFrameStartsAtZero() {
        val t = UrevoDriver.decodeFff1(runStart)!!
        assertEquals(BeltStatus.RUNNING, t.status); assertEquals(0, t.elapsedSec); assertEquals(0.0, t.kcal!!, 1e-9)
    }

    @Test fun pausingAndPausedFrames() {
        assertEquals(BeltStatus.PAUSING, UrevoDriver.decodeFff1(pausing)!!.status)
        val p = UrevoDriver.decodeFff1(paused)!!
        assertEquals(BeltStatus.PAUSED, p.status); assertEquals(76, p.elapsedSec); assertEquals(4.6, p.kcal!!, 1e-9)
    }

    @Test fun statusMapping() {
        val expected = mapOf(
            0x00 to BeltStatus.IDLE, 0x01 to BeltStatus.STOPPED, 0x02 to BeltStatus.STARTING, 0x03 to BeltStatus.RUNNING,
            0x04 to BeltStatus.PAUSING, 0x0a to BeltStatus.PAUSED, 0x7f to BeltStatus.UNKNOWN,
        )
        for ((raw, status) in expected) assertEquals(status, UrevoDriver.decodeFff1(frame(raw, 6))!!.status)
    }

    @Test fun shortFramesHaveNoElapsedOrKcal() {
        val t = UrevoDriver.decodeFff1(frame(0x03, 19))!!
        assertNull(t.elapsedSec); assertNull(t.kcal)
    }

    @Test fun implausibleFff1KcalIsDropped() {
        val f = run63s.copyOf().also { it[9] = 0xFF.toByte(); it[10] = 0xFF.toByte() }      // 6553.5 kcal
        val t = UrevoDriver.decodeFff1(f)!!
        assertNull(t.kcal); assertEquals(63, t.elapsedSec)
    }

    @Test fun malformedFff1FramesReturnNull() {
        assertNull(UrevoDriver.decodeFff1(ByteArray(0)))
        assertNull(UrevoDriver.decodeFff1(byteArrayOf(0x02)))
        assertNull(UrevoDriver.decodeFff1(byteArrayOf(0x02, 0x51, 0x03, 0x14, 0x00)))       // 5 bytes
        assertNull(UrevoDriver.decodeFff1(run63s.copyOf().also { it[1] = 0x50 }))           // wrong header
        assertNull(UrevoDriver.decodeFff1(run63s.copyOf().also { it[0] = 0x00 }))
    }

    // --- FTMS Treadmill Data (0x2ACD): speed 0.01 km/h, distance m, energy kcal, elapsed s ---
    @Test fun ftmsRunningFrame() {
        val r = UrevoDriver.decodeFtms(ftmsFast)!!
        assertEquals(6.43, r.speedKmh!!, 1e-9); assertEquals(93.0, r.distanceM!!, 1e-9)
        assertEquals(4.0, r.kcal!!, 1e-9); assertEquals(76, r.elapsedSec)
    }

    @Test fun ftmsSlowAndEarlyFrames() {
        val a = UrevoDriver.decodeFtms(ftmsSlow)!!
        assertEquals(0.96, a.speedKmh!!, 1e-9); assertEquals(0.0, a.distanceM!!, 1e-9); assertEquals(0.0, a.kcal!!, 1e-9); assertEquals(0, a.elapsedSec)
        val b = UrevoDriver.decodeFtms(ftmsEarly)!!
        assertEquals(2.57, b.speedKmh!!, 1e-9); assertEquals(4.0, b.distanceM!!, 1e-9); assertEquals(11, b.elapsedSec)
    }

    @Test fun ftmsUnavailableEnergyIsNull() {
        val f = ftmsFast.copyOf().also { it[7] = 0xFF.toByte(); it[8] = 0xFF.toByte() }     // total energy 0xFFFF = not available
        assertNull(UrevoDriver.decodeFtms(f)!!.kcal)
    }

    @Test fun ftmsImplausibleSpeedIsDropped() {
        val f = ftmsFast.copyOf().also { it[2] = 0x70; it[3] = 0x17 }                       // 0x1770 = 6000 -> 60.00 km/h
        assertNull(UrevoDriver.decodeFtms(f)!!.speedKmh)
        assertNotNull(UrevoDriver.decodeFtms(f)!!.distanceM)
    }

    @Test fun truncatedOrEmptyFtmsFramesReturnNull() {
        assertNull(UrevoDriver.decodeFtms(ByteArray(0)))
        assertNull(UrevoDriver.decodeFtms(byteArrayOf(0x84.toByte())))
        assertNull(UrevoDriver.decodeFtms(byteArrayOf(0x84.toByte(), 0x04)))                // flags only
        assertNull(UrevoDriver.decodeFtms(ftmsFast.copyOf(8)))                              // cut inside the energy field
        assertNull(UrevoDriver.decodeFtms(ftmsFast.copyOf(13)))                             // cut inside elapsed time
    }
}
