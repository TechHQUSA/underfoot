package org.walkpadhealth.protocol

/** STARTING is the 3-2-1 countdown before the belt moves (status byte 0x02, seen on URTM059). */
enum class BeltStatus { IDLE, STARTING, STOPPED, RUNNING, PAUSING, PAUSED, UNKNOWN }

data class Telemetry(
    val status: BeltStatus,
    val speedKmh: Double?,
    val distanceM: Double? = null,
    val steps: Int? = null,
    val kcal: Double? = null,
)
