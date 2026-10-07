package org.walkpadhealth

import org.walkpadhealth.ble.FrameLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameLogTest {
    private fun tmp() = Files.createTempDirectory("fl").toFile()

    @Test fun appendsHexLinesWithSource() {
        val log = FrameLog(tmp())
        log.append("fff1", byteArrayOf(0x02, 0x51, 0x0a))
        val parts = log.file().readLines().single().split('\t')
        assertEquals("fff1", parts[1]); assertEquals("02510a", parts[2])
    }

    @Test fun highBytesAreNotSignExtended() {
        val log = FrameLog(tmp())
        log.append("fff1", byteArrayOf(0x80.toByte(), 0xff.toByte(), 0x00))
        assertEquals("80ff00", log.file().readLines().single().split('\t')[2])
    }

    @Test fun truncatesOldestHalfWhenOverTheCap() {
        val log = FrameLog(tmp(), maxBytes = 2_000)
        repeat(200) { log.append("fff1", ByteArray(8) { it.toByte() }) }
        assertTrue(log.file().length() <= 2_000, "size ${log.file().length()}")
        assertTrue(log.file().readLines().isNotEmpty())
    }

    @Test fun clearEmptiesTheFile() {
        val log = FrameLog(tmp()); log.append("fff1", byteArrayOf(1)); log.clear()
        assertEquals(0L, log.file().length())
    }
}
