package org.walkpadhealth.protocol

/** STARTING is the 3-2-1 countdown before the belt moves (status byte 0x02, seen on URTM059). */
enum class BeltStatus { IDLE, STARTING, STOPPED, RUNNING, PAUSING, PAUSED, UNKNOWN }

data class Telemetry(
    val status: BeltStatus,
    val speedKmh: Double?,
    val distanceM: Double? = null,
    val steps: Int? = null,
    val kcal: Double? = null,
    val elapsedSec: Int? = null,
)

/** One Treadmill Data (0x2ACD) notification. All values are in SI units; null when the frame does not carry them. */
data class FtmsReading(
    val speedKmh: Double?,
    val distanceM: Double?,
    val kcal: Double?,
    val elapsedSec: Int?,
)

/**
 * fff1 gives the belt status, elapsed time and tenths of a kcal; the standard Treadmill Data stream gives speed and distance in
 * SI units. fff1's own speed and distance fields use miles, so they are deliberately not decoded. Not thread-safe.
 */
class TelemetryMerger {
    private var ftms: FtmsReading? = null

    fun onFtms(r: FtmsReading) { ftms = r }
    fun reset() { ftms = null }

    fun merge(t: Telemetry): Telemetry {
        val f = ftms ?: return t
        return t.copy(
            speedKmh = t.speedKmh ?: f.speedKmh,
            distanceM = t.distanceM ?: f.distanceM,
            kcal = t.kcal ?: f.kcal,
            elapsedSec = t.elapsedSec ?: f.elapsedSec,
        )
    }
}
