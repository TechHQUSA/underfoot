package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FtmsControlTest {
    @Test fun commandFramesAreExactlyWhatWasMeasuredOnThePad() {
        assertContentEquals(byteArrayOf(0x00), FtmsControl.requestControl)
        assertContentEquals(byteArrayOf(0x08, 0x02), FtmsControl.frame(PadCommand.PAUSE))
        assertContentEquals(byteArrayOf(0x07), FtmsControl.frame(PadCommand.RESUME))
        assertContentEquals(byteArrayOf(0x08, 0x01), FtmsControl.frame(PadCommand.STOP))
    }

    @Test fun repliesAreParsedFromIndications() {
        val ok = FtmsControl.parseReply(byteArrayOf(0x80.toByte(), 0x08, 0x01))!!
        assertEquals(8, ok.opcode); assertTrue(ok.ok)
        val refused = FtmsControl.parseReply(byteArrayOf(0x80.toByte(), 0x07, 0x03))!!
        assertEquals(7, refused.opcode); assertFalse(refused.ok)
    }

    @Test fun malformedRepliesAreNull() {
        assertNull(FtmsControl.parseReply(ByteArray(0)))
        assertNull(FtmsControl.parseReply(byteArrayOf(0x80.toByte(), 0x08)))
        assertNull(FtmsControl.parseReply(byteArrayOf(0x00, 0x08, 0x01)))
    }

    @Test fun opcodesAreTiedToTheirCommands() {
        assertEquals(0x08, FtmsControl.opcode(PadCommand.PAUSE)); assertEquals(0x08, FtmsControl.opcode(PadCommand.STOP))
        assertEquals(0x07, FtmsControl.opcode(PadCommand.RESUME))
    }

    @Test fun buttonsAreOnlyOfferedWhereTheyMakeSense() {
        assertEquals(setOf(PadCommand.PAUSE, PadCommand.STOP), FtmsControl.allowed(BeltStatus.RUNNING, true))
        assertEquals(setOf(PadCommand.RESUME, PadCommand.STOP), FtmsControl.allowed(BeltStatus.PAUSED, true))
        assertEquals(setOf(PadCommand.STOP), FtmsControl.allowed(BeltStatus.PAUSING, true))
        assertEquals(setOf(PadCommand.STOP), FtmsControl.allowed(BeltStatus.STARTING, true))
        assertEquals(setOf(PadCommand.STOP), FtmsControl.allowed(BeltStatus.UNKNOWN, true))      // unsure the belt is still: allow the safe action
        assertEquals(emptySet(), FtmsControl.allowed(BeltStatus.IDLE, true))
        assertEquals(emptySet(), FtmsControl.allowed(BeltStatus.STOPPED, true))
    }

    @Test fun aCommandIsConfirmedWhenTheBeltStatusMovesTheExpectedWay() {
        assertTrue(FtmsControl.confirmedBy(PadCommand.PAUSE, BeltStatus.PAUSING)); assertTrue(FtmsControl.confirmedBy(PadCommand.PAUSE, BeltStatus.PAUSED))
        assertFalse(FtmsControl.confirmedBy(PadCommand.PAUSE, BeltStatus.RUNNING))
        assertTrue(FtmsControl.confirmedBy(PadCommand.RESUME, BeltStatus.STARTING)); assertTrue(FtmsControl.confirmedBy(PadCommand.RESUME, BeltStatus.RUNNING))
        assertFalse(FtmsControl.confirmedBy(PadCommand.RESUME, BeltStatus.PAUSED))
        assertTrue(FtmsControl.confirmedBy(PadCommand.STOP, BeltStatus.IDLE)); assertTrue(FtmsControl.confirmedBy(PadCommand.STOP, BeltStatus.PAUSING))
        assertFalse(FtmsControl.confirmedBy(PadCommand.STOP, BeltStatus.RUNNING))
    }

    @Test fun nothingIsAllowedWhileDisconnected() {
        for (s in BeltStatus.entries) assertEquals(emptySet(), FtmsControl.allowed(s, false))
    }

    @Test fun pauseAndResumeAreRateLimitedButStopNeverIs() {
        val g = CommandGate(minGapMs = 1000)
        assertTrue(g.accept(PadCommand.PAUSE, 0))
        assertFalse(g.accept(PadCommand.RESUME, 500))          // too soon after another command
        assertTrue(g.accept(PadCommand.STOP, 600))             // stop is never held back
        assertTrue(g.accept(PadCommand.STOP, 601))
        assertTrue(g.accept(PadCommand.RESUME, 1700))          // gap measured from the last accepted command
    }
}
