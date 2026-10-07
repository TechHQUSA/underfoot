package org.walkpadhealth.protocol

import kotlin.math.roundToInt

object Estimators {
    private fun ok(v: Double) = v.isFinite() && v > 0.0

    /** ACSM level-walking equation, gross kcal: VO2 = 0.1*S(m/min) + 3.5 ml/kg/min, 5 kcal per L O2. */
    fun kcal(distanceM: Double, durationSec: Double, weightKg: Double): Double {
        if (!ok(distanceM) || !ok(durationSec) || !ok(weightKg)) return 0.0
        val minutes = durationSec / 60.0
        return (0.1 * distanceM + 3.5 * minutes) * weightKg * 0.005
    }

    /** Stride = 0.414 x height. */
    fun steps(distanceM: Double, heightCm: Double): Int {
        if (!ok(distanceM) || !ok(heightCm)) return 0
        return (distanceM / (0.414 * heightCm / 100.0)).roundToInt()
    }
}
