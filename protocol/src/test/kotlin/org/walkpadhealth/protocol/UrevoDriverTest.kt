package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrevoDriverTest {
    // Synthetic frames built from PROTOCOL.md (5L/E1L family). Replace with real URTM059 captures in Task 7.
    private fun frame(status: Int, speedRaw: Int, size: Int): ByteArray = ByteArray(size).also {
        it[0] = 0x02; it[1] = 0x51; it[2] = status.toByte()
        it[3] = (speedRaw and 0xFF).toByte(); it[4] = ((speedRaw shr 8) and 0xFF).toByte()
        it[size - 1] = 0x03
    }

    @Test fun handshakeFramesAreExact() {
        assertContentEquals(byteArrayOf(0x02, 0x51, 0x0b, 0x03), UrevoDriver.handshakeFrames[0])
        assertContentEquals(byteArrayOf(0x02, 0x50, 0x03, 0x09, 0x03), UrevoDriver.handshakeFrames[1])
    }

    @Test fun idlePingHasStatusAndNoSpeed() {
        val t = UrevoDriver.decodeFff1(byteArrayOf(0x02, 0x51, 0x00, 0x00, 0x00, 0x03))!!
        assertEquals(BeltStatus.IDLE, t.status)
        assertNull(t.speedKmh)
    }

    @Test fun runningFrameDecodesSpeedInTenthsKmh() {
        val t = UrevoDriver.decodeFff1(frame(0x03, 20, 19))!!
        assertEquals(BeltStatus.RUNNING, t.status)
        assertEquals(2.0, t.speedKmh!!, 1e-9)
    }

    @Test fun speedIsLittleEndianU16() {
        assertEquals(6.0, UrevoDriver.decodeFff1(frame(0x03, 0x3c, 19))!!.speedKmh!!, 1e-9)
        assertEquals(25.6, UrevoDriver.decodeFff1(frame(0x03, 0x0100, 19))!!.speedKmh!!, 1e-9)
    }

    @Test fun twentyFiveByteFrameDecodesTheSame() {
        assertEquals(3.5, UrevoDriver.decodeFff1(frame(0x03, 35, 25))!!.speedKmh!!, 1e-9)
    }

    @Test fun statusMapping() {
        val expected = mapOf(
            0x00 to BeltStatus.IDLE, 0x01 to BeltStatus.STOPPED, 0x03 to BeltStatus.RUNNING,
            0x04 to BeltStatus.PAUSING, 0x0a to BeltStatus.PAUSED, 0x7f to BeltStatus.UNKNOWN,
        )
        for ((raw, status) in expected) assertEquals(status, UrevoDriver.decodeFff1(frame(raw, 0, 19))!!.status)
    }

    @Test fun implausibleSpeedIsDroppedNotReported() {
        // a wrong frame layout or noise must not become 60 km/h and poison distance (Health Connect rejects huge values)
        assertNull(UrevoDriver.decodeFff1(frame(0x03, 600, 19))!!.speedKmh)
        assertEquals(30.0, UrevoDriver.decodeFff1(frame(0x03, 300, 19))!!.speedKmh!!, 1e-9)
    }

    @Test fun eighteenByteFrameHasNoSpeed() {
        assertNull(UrevoDriver.decodeFff1(frame(0x03, 20, 18))!!.speedKmh)
    }

    @Test fun malformedFramesReturnNull() {
        assertNull(UrevoDriver.decodeFff1(ByteArray(0)))
        assertNull(UrevoDriver.decodeFff1(byteArrayOf(0x02)))
        assertNull(UrevoDriver.decodeFff1(byteArrayOf(0x02, 0x51, 0x03, 0x14, 0x00)))      // 5 bytes
        assertNull(UrevoDriver.decodeFff1(frame(0x03, 20, 19).also { it[1] = 0x50 }))      // wrong header
        assertNull(UrevoDriver.decodeFff1(frame(0x03, 20, 19).also { it[0] = 0x00 }))
    }
}
