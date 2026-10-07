package org.walkpadhealth.protocol

enum class PadCommand { PAUSE, RESUME, STOP }

/**
 * The only commands the app ever sends to the pad, through the standard FTMS Control Point (0x2AD9). All three were measured on a
 * URTM059 with nobody on the belt: pause (08 02) holds the belt paused, resume (07) restarts it, stop (08 01) slows it and ends
 * the workout (the console shows END). `00` (request control) is sent once per connection before the first command.
 */
object FtmsControl {
    val requestControl: ByteArray = byteArrayOf(0x00)

    fun frame(c: PadCommand): ByteArray = when (c) {
        PadCommand.PAUSE -> byteArrayOf(0x08, 0x02)
        PadCommand.RESUME -> byteArrayOf(0x07)
        PadCommand.STOP -> byteArrayOf(0x08, 0x01)
    }

    fun opcode(c: PadCommand): Int = frame(c)[0].toInt()

    /** An indication from 0x2AD9: `80 <request opcode> <result>`; result 01 means success. */
    data class Reply(val opcode: Int, val result: Int) { val ok: Boolean get() = result == 0x01 }

    fun parseReply(f: ByteArray): Reply? {
        if (f.size < 3 || f[0] != 0x80.toByte()) return null
        return Reply(f[1].toInt() and 0xFF, f[2].toInt() and 0xFF)
    }

    /** The belt status that shows a command took effect, used when the pad does not send a control-point reply. */
    fun confirmedBy(c: PadCommand, s: BeltStatus): Boolean = when (c) {
        PadCommand.PAUSE -> s == BeltStatus.PAUSING || s == BeltStatus.PAUSED
        PadCommand.RESUME -> s == BeltStatus.STARTING || s == BeltStatus.RUNNING
        PadCommand.STOP -> s != BeltStatus.RUNNING
    }

    /** Which buttons make sense for a belt status. Stop is offered whenever the belt might be moving. Nothing while disconnected. */
    fun allowed(status: BeltStatus, connected: Boolean): Set<PadCommand> = when {
        !connected -> emptySet()
        status == BeltStatus.RUNNING -> setOf(PadCommand.PAUSE, PadCommand.STOP)
        status == BeltStatus.PAUSED -> setOf(PadCommand.RESUME, PadCommand.STOP)
        status == BeltStatus.PAUSING || status == BeltStatus.STARTING || status == BeltStatus.UNKNOWN -> setOf(PadCommand.STOP)
        else -> emptySet()
    }
}

/** Rate-limits pause and resume. Stop is never held back, so a quick tap on Stop right after Pause always goes through. */
class CommandGate(private val minGapMs: Long = 1_000) {
    private var lastMs = Long.MIN_VALUE / 2

    fun accept(c: PadCommand, nowMs: Long): Boolean {
        if (c != PadCommand.STOP && nowMs - lastMs < minGapMs) return false
        lastMs = nowMs
        return true
    }
}
