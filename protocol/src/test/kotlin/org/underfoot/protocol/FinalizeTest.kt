// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class FinalizeTest {
    private fun sum(devDist: Double? = null, devSteps: Int? = null, devKcal: Double? = null) =
        SessionSummary(0, 3_600_000, 3600, 3000.0, devDist, devSteps, devKcal)

    @Test fun estimatesEverythingWhenPadReportsNothing() {
        val f = sum().finalize(Profile(70.0, 175.0))
        assertEquals(3000.0, f.distanceM); assertEquals(Source.ESTIMATED, f.distanceSource)
        assertEquals(4141, f.steps);       assertEquals(Source.ESTIMATED, f.stepsSource)   // 3000 / 0.7245
        assertEquals(178.5, f.kcal, 0.01); assertEquals(Source.ESTIMATED, f.kcalSource)
    }

    @Test fun deviceValuesWin() {
        val f = sum(devDist = 2500.0, devSteps = 3000, devKcal = 200.0).finalize(Profile.DEFAULT)
        assertEquals(2500.0, f.distanceM); assertEquals(Source.DEVICE, f.distanceSource)
        assertEquals(3000, f.steps);       assertEquals(Source.DEVICE, f.stepsSource)
        assertEquals(200.0, f.kcal);       assertEquals(Source.DEVICE, f.kcalSource)
    }

    @Test fun zeroDeviceDistanceFallsBackToIntegrated() {
        val f = sum(devDist = 0.0).finalize(Profile.DEFAULT)
        assertEquals(3000.0, f.distanceM); assertEquals(Source.ESTIMATED, f.distanceSource)
    }

    @Test fun infiniteDeviceValuesAreIgnored() {
        val f = sum(devDist = Double.POSITIVE_INFINITY, devKcal = Double.POSITIVE_INFINITY).finalize(Profile(70.0, 175.0))
        assertEquals(3000.0, f.distanceM); assertEquals(Source.ESTIMATED, f.distanceSource)
        assertEquals(Source.ESTIMATED, f.kcalSource)
    }

    @Test fun wallClockTimesKeepTheMonotonicDuration() {
        val s = SessionSummary(5_000, 65_000, 55, 0.0, null, null, null, wallStartMs = 1_700_000_000_000)
        val w = s.withWallClock()
        assertEquals(1_700_000_000_000L, w.startMs); assertEquals(1_700_000_060_000L, w.endMs)
        assertEquals(55L, w.activeSec)
    }

    @Test fun badProfileGivesZeroEstimatesNotNaN() {
        val f = sum().finalize(Profile(Double.NaN, -5.0))
        assertEquals(0, f.steps); assertEquals(0.0, f.kcal)
    }

    @Test fun pausesMoveWithTheWallClockAndSurviveFinalize() {
        val s = SessionSummary(1_000, 61_000, 50, 40.0, null, 60, null, wallStartMs = 1_700_000_000_000L, pauses = listOf(11_000L to 21_000L))
        val w = s.withWallClock()
        assertEquals(listOf(1_700_000_010_000L to 1_700_000_020_000L), w.pauses)
        assertEquals(w.pauses, w.finalize(Profile.DEFAULT).pauses)
    }
}
