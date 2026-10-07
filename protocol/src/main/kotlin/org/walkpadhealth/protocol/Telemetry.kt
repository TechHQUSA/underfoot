package org.walkpadhealth.protocol

enum class BeltStatus { IDLE, STOPPED, RUNNING, PAUSING, PAUSED, UNKNOWN }

data class Telemetry(
    val status: BeltStatus,
    val speedKmh: Double?,
    val distanceM: Double? = null,
    val steps: Int? = null,
    val kcal: Double? = null,
)
