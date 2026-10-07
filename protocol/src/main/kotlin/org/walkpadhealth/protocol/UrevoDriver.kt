package org.walkpadhealth.protocol

object UrevoDriver {
    /** Written to fff2 after connecting; the pad acknowledges the second one with `02 50 03 00 00 59 F6 03` on fff1. */
    val handshakeFrames: List<ByteArray> = listOf(
        byteArrayOf(0x02, 0x51, 0x0b, 0x03),
        byteArrayOf(0x02, 0x50, 0x03, 0x09, 0x03),
    )

    private const val MIN_FRAME = 6
    private const val TELEMETRY_FRAME = 25
    /** Walking pads top out near 6.5 km/h; anything above this is noise or a wrong layout, not a speed. */
    private const val MAX_SPEED_KMH = 30.0
    private const val MAX_KCAL = 5_000.0

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /**
     * Frames `02 51 <status> ...`. A 6-byte frame is the once-a-second idle ping; the 25-byte running frame also carries
     * elapsed seconds (u16 at 5) and energy in tenths of a kcal (u16 at 9). Returns null for anything else. Never throws.
     */
    fun decodeFff1(frame: ByteArray): Telemetry? {
        if (frame.size < MIN_FRAME || frame[0] != 0x02.toByte() || frame[1] != 0x51.toByte()) return null
        val status = when (frame[2].toInt() and 0xFF) {
            0x00 -> BeltStatus.IDLE
            0x01 -> BeltStatus.STOPPED
            0x02 -> BeltStatus.STARTING
            0x03 -> BeltStatus.RUNNING
            0x04 -> BeltStatus.PAUSING
            0x0a -> BeltStatus.PAUSED
            else -> BeltStatus.UNKNOWN
        }
        if (frame.size < TELEMETRY_FRAME) return Telemetry(status, null)
        val elapsed = u16(frame, 5)
        val kcal = (u16(frame, 9) / 10.0).takeIf { it <= MAX_KCAL }
        return Telemetry(status, null, kcal = kcal, elapsedSec = elapsed)
    }

    private class Cursor(val b: ByteArray) {
        var pos = 0
        fun u(n: Int): Int? {
            if (pos + n > b.size) return null
            var v = 0
            for (k in 0 until n) v = v or ((b[pos + k].toInt() and 0xFF) shl (8 * k))
            pos += n
            return v
        }
        fun skip(n: Int): Boolean { if (pos + n > b.size) return false; pos += n; return true }
    }

    /**
     * Standard FTMS Treadmill Data: flags u16, then (in order) speed 0.01 km/h, average speed, total distance u24 m, inclination,
     * elevation, pace, energy (total u16 kcal, per hour u16, per minute u8), heart rate, MET, elapsed s u16. Returns null if the
     * frame is cut short. Never throws.
     */
    fun decodeFtms(frame: ByteArray): FtmsReading? {
        val c = Cursor(frame)
        val flags = c.u(2) ?: return null
        var speed: Double? = null
        if (flags and 0x0001 == 0) speed = ((c.u(2) ?: return null) / 100.0).takeIf { it <= MAX_SPEED_KMH }
        if (flags and 0x0002 != 0 && !c.skip(2)) return null            // average speed
        var distance: Double? = null
        if (flags and 0x0004 != 0) distance = (c.u(3) ?: return null).toDouble()
        if (flags and 0x0008 != 0 && !c.skip(4)) return null            // inclination + ramp angle
        if (flags and 0x0010 != 0 && !c.skip(4)) return null            // elevation gain
        if (flags and 0x0020 != 0 && !c.skip(1)) return null            // instantaneous pace
        if (flags and 0x0040 != 0 && !c.skip(1)) return null            // average pace
        var kcal: Double? = null
        if (flags and 0x0080 != 0) {
            val total = c.u(2) ?: return null
            if (!c.skip(3)) return null                                  // energy per hour, energy per minute
            kcal = total.takeIf { it != 0xFFFF }?.toDouble()?.takeIf { it <= MAX_KCAL }
        }
        if (flags and 0x0100 != 0 && !c.skip(1)) return null            // heart rate
        if (flags and 0x0200 != 0 && !c.skip(1)) return null            // metabolic equivalent
        var elapsed: Int? = null
        if (flags and 0x0400 != 0) elapsed = c.u(2) ?: return null
        return FtmsReading(speed, distance, kcal, elapsed)
    }
}
