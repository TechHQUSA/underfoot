package org.walkpadhealth.protocol

enum class Source { DEVICE, ESTIMATED }

data class Profile(val weightKg: Double, val heightCm: Double) {
    companion object { val DEFAULT = Profile(70.0, 170.0) }
}

data class SessionSummary(
    val startMs: Long,
    val endMs: Long,
    val activeSec: Long,
    val integratedDistanceM: Double,
    val deviceDistanceM: Double?,
    val deviceSteps: Int?,
    val deviceKcal: Double?,
    val wallStartMs: Long = 0L,   // epoch time at the start frame; startMs/endMs are monotonic
)

data class FinalSession(
    val startMs: Long,
    val endMs: Long,
    val activeSec: Long,
    val distanceM: Double,
    val distanceSource: Source,
    val steps: Int,
    val stepsSource: Source,
    val kcal: Double,
    val kcalSource: Source,
)

fun SessionSummary.finalize(profile: Profile): FinalSession {
    val devDist = deviceDistanceM?.takeIf { it.isFinite() && it > 0.0 }
    val dist = devDist ?: integratedDistanceM.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val steps = deviceSteps?.takeIf { it > 0 }
    val kcal = deviceKcal?.takeIf { it.isFinite() && it > 0.0 }
    return FinalSession(
        startMs, endMs, activeSec,
        dist, if (devDist != null) Source.DEVICE else Source.ESTIMATED,
        steps ?: Estimators.steps(dist, profile.heightCm), if (steps != null) Source.DEVICE else Source.ESTIMATED,
        kcal ?: Estimators.kcal(dist, activeSec.toDouble(), profile.weightKg), if (kcal != null) Source.DEVICE else Source.ESTIMATED,
    )
}
