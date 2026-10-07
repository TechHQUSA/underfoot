package org.walkpadhealth.ble

import java.io.File

/** Append-only hex log of raw BLE frames, used to map the pad's fields (Phase 0) and for bug reports. */
class FrameLog(dir: File, private val maxBytes: Long = 5_000_000) {
    private val f = File(dir.apply { mkdirs() }, "frames.log")

    fun file(): File = f

    @Synchronized fun append(source: String, bytes: ByteArray) {
        f.appendText("${System.currentTimeMillis()}\t$source\t${bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}\n")
        if (f.length() > maxBytes) {
            val lines = f.readLines()
            f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized fun clear() { f.writeText("") }
}
