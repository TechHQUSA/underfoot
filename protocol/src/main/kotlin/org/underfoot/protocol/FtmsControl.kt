package org.underfoot.protocol

enum class PadCommand { START, PAUSE, RESUME, STOP }

/**
 * The only commands the app ever sends to the pad, through the standard FTMS Control Point (0x2AD9). All three were measured on a
 * URTM059 with nobody on the belt: pause (08 02) holds the belt paused, resume (07) restarts it, stop (08 01) slows it and ends
 * the workout (the console shows END). `00` (request control) is sent once per connection before the first command.
 */
object FtmsControl {
    val requestControl: ByteArray = byteArrayOf(0x00)

    fun frame(c: PadCommand): ByteArray = when (c) {
        PadCommand.PAUSE -> byteArrayOf(0x08, 0x02)
        PadCommand.START, PadCommand.RESUME -> byteArrayOf(0x07)       // FTMS "start or resume": the same bytes
        PadCommand.STOP -> byteArrayOf(0x08, 0x01)
    }

    /** FTMS "Set Target Speed" (opcode 02, u16 little-endian, 0.01 km/h). Not yet measured on a URTM059: a refusal shows as FAILED. */
    fun setSpeedFrame(kmh: Double): ByteArray {
        val v = Math.round(kmh * 100).toInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(0x02, (v and 0xFF).toByte(), (v shr 8).toByte())
    }

    const val OPCODE_SET_SPEED = 0x02

    /** The belt speed can be changed only while it moves; nothing while disconnected. */
    fun canSetSpeed(status: BeltStatus, connected: Boolean): Boolean = connected && status == BeltStatus.RUNNING

    fun opcode(c: PadCommand): Int = frame(c)[0].toInt()

    /** An indication from 0x2AD9: `80 <request opcode> <result>`; result 01 means success. */
    data class Reply(val opcode: Int, val result: Int) { val ok: Boolean get() = result == 0x01 }

    fun parseReply(f: ByteArray): Reply? {
        if (f.size < 3 || f[0] != 0x80.toByte()) return null
        return Reply(f[1].toInt() and 0xFF, f[2].toInt() and 0xFF)
    }

    /**
     * Whether a belt status change proves a command took effect, used when the pad sends no control-point reply. `before` is the
     * status when the command was sent. A status that did not change proves nothing, so a command is never confirmed by a state
     * the belt was already in; a stop from anything but a moving belt is confirmed only by the walk actually ending.
     */
    fun confirmedBy(c: PadCommand, before: BeltStatus, now: BeltStatus): Boolean = when (c) {
        PadCommand.START -> (before == BeltStatus.IDLE || before == BeltStatus.STOPPED) && (now == BeltStatus.STARTING || now == BeltStatus.RUNNING)
        PadCommand.PAUSE -> before == BeltStatus.RUNNING && (now == BeltStatus.PAUSING || now == BeltStatus.PAUSED)
        PadCommand.RESUME -> (before == BeltStatus.PAUSED || before == BeltStatus.PAUSING) && (now == BeltStatus.STARTING || now == BeltStatus.RUNNING)
        PadCommand.STOP ->
            if (before == BeltStatus.RUNNING) now != BeltStatus.RUNNING
            else now == BeltStatus.IDLE || now == BeltStatus.STOPPED
    }

    /** Which buttons make sense for a belt status. Stop is offered whenever the belt might be moving. Nothing while disconnected. */
    fun allowed(status: BeltStatus, connected: Boolean): Set<PadCommand> = when {
        !connected -> emptySet()
        status == BeltStatus.RUNNING -> setOf(PadCommand.PAUSE, PadCommand.STOP)
        status == BeltStatus.PAUSED -> setOf(PadCommand.RESUME, PadCommand.STOP)
        status == BeltStatus.PAUSING || status == BeltStatus.STARTING || status == BeltStatus.UNKNOWN -> setOf(PadCommand.STOP)
        else -> setOf(PadCommand.START)                                // idle or stopped
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

/** The speeds a person can ask for. The pad tops out at 4.0 mph; the lower limit, 0.6 mph, is the slowest this model runs. */
object SpeedTarget {
    const val MPH_TO_KMH = 1.609344
    const val MAX_KMH = 4.0 * MPH_TO_KMH
    const val MIN_KMH = 0.6 * MPH_TO_KMH

    private fun unit(imperial: Boolean) = if (imperial) MPH_TO_KMH else 1.0

    /** Rounds to a tenth of the displayed unit and keeps the result inside the pad's range. */
    fun snap(kmh: Double, imperial: Boolean): Double {
        if (!kmh.isFinite()) return MIN_KMH
        val u = unit(imperial)
        return (Math.round(kmh / u * 10) / 10.0 * u).coerceIn(MIN_KMH, MAX_KMH)
    }

    /** One tap on + or -: a tenth of the displayed unit. */
    fun step(kmh: Double, direction: Int, imperial: Boolean): Double =
        snap(Math.round(kmh / unit(imperial) * 10) / 10.0 * unit(imperial) + direction.coerceIn(-1, 1) * 0.1 * unit(imperial), imperial)
}
