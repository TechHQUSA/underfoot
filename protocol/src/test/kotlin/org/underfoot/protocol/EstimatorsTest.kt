package org.underfoot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class EstimatorsTest {
    @Test fun kcalMatchesAcsmWalkingEquation() {
        // 3 km/h for 60 min = 3000 m, 70 kg: (0.1*3000 + 3.5*60) * 70 * 0.005 = 178.5
        assertEquals(178.5, Estimators.kcal(3000.0, 3600.0, 70.0), 0.01)
    }

    @Test fun stepsUseStrideFromHeight() {
        // stride = 0.414 * 1.75 = 0.7245 m; 1000 / 0.7245 = 1380.26
        assertEquals(1380, Estimators.steps(1000.0, 175.0))
    }

    @Test fun badInputsGiveZeroNotNaN() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)
        for (b in bad) {
            assertEquals(0.0, Estimators.kcal(b, 3600.0, 70.0))
            assertEquals(0.0, Estimators.kcal(3000.0, b, 70.0))
            assertEquals(0.0, Estimators.kcal(3000.0, 3600.0, b))
            assertEquals(0, Estimators.steps(b, 175.0))
            assertEquals(0, Estimators.steps(1000.0, b))
        }
    }

    @Test fun zeroDistanceGivesZeroKcal() {
        assertEquals(0.0, Estimators.kcal(0.0, 3600.0, 70.0))   // no movement: not a walking session
    }
}
