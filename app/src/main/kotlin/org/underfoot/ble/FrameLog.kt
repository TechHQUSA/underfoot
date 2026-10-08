package org.underfoot.ble

import java.io.File
import java.io.IOException

/** Append-only hex log of raw BLE frames, used to map the pad's fields (Phase 0) and for bug reports. */
class FrameLog(dir: File, private val maxBytes: Long = 5_000_000) {
    private val f = File(dir.apply { mkdirs() }, "frames.log")
    private val old = File(dir, "frames.log.old")

    fun file(): File = f

    /** Logging is best effort: a full disk must not crash the BLE callback. Over the cap, the log rolls to `.old` (a rename, not a rewrite). */
    @Synchronized fun append(source: String, bytes: ByteArray) {
        try {
            if (f.length() > maxBytes / 2) { old.delete(); f.renameTo(old) }
            f.appendText("${System.currentTimeMillis()}\t$source\t${bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}\n")
        } catch (_: IOException) {}
    }

    @Synchronized fun clear() { try { f.writeText(""); old.delete() } catch (_: IOException) {} }
}
