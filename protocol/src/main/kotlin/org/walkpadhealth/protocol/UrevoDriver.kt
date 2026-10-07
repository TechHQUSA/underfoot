package org.walkpadhealth.protocol

object UrevoDriver {
    /** Written to fff2 after connecting; fff1 stays silent without them. */
    val handshakeFrames: List<ByteArray> = listOf(
        byteArrayOf(0x02, 0x51, 0x0b, 0x03),
        byteArrayOf(0x02, 0x50, 0x03, 0x09, 0x03),
    )

    private const val MIN_FRAME = 6
    private const val SPEED_FRAME = 19

    /** Returns null for anything that is not a well-formed `02 51 ...` frame. Never throws. */
    fun decodeFff1(frame: ByteArray): Telemetry? {
        if (frame.size < MIN_FRAME || frame[0] != 0x02.toByte() || frame[1] != 0x51.toByte()) return null
        val status = when (frame[2].toInt() and 0xFF) {
            0x00 -> BeltStatus.IDLE
            0x01 -> BeltStatus.STOPPED
            0x03 -> BeltStatus.RUNNING
            0x04 -> BeltStatus.PAUSING
            0x0a -> BeltStatus.PAUSED
            else -> BeltStatus.UNKNOWN
        }
        val speed = if (frame.size >= SPEED_FRAME) {
            ((frame[3].toInt() and 0xFF) or ((frame[4].toInt() and 0xFF) shl 8)) / 10.0
        } else null
        return Telemetry(status, speed)
    }
}
