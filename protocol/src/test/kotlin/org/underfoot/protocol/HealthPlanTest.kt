// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HealthPlanTest {
    private val p = "underfoot-x-7"

    @Test fun pausesRoundTripThroughTheirStoredForm() {
        val list = listOf(1_000L to 2_000L, 5_000L to 9_000L)
        assertEquals(PauseList.Valid(list), PauseList.decode(PauseList.encode(list), 0, 10_000))
        assertEquals(PauseList.Valid(emptyList()), PauseList.decode("", 0, 10_000))
    }

    @Test fun damagedPausesAreRejectedNotTreatedAsNone() {
        for (bad in listOf("x", "1-", "1-2,", "5-3", "1-2,2-1", "1-5,4-8", "0-2", "1-11", "3-3"))
            assertIs<PauseList.Invalid>(PauseList.decode(bad, 1, 10), bad)
    }

    @Test fun aWalkWithoutPausesIsWrittenExactlyAsBefore() {
        val h = HealthPlan.of(p, 0, 600_000, emptyList(), steps = 900, distanceM = 500.0, kcal = 40.0)
        assertEquals(HealthPlan.Session("$p-exercise", 0, 600_000, emptyList()), h.session)
        assertEquals(listOf(HealthPlan.Amount("$p-steps", 0, 600_000, 900.0)), h.steps)
        assertEquals(listOf(HealthPlan.Amount("$p-distance", 0, 600_000, 500.0)), h.distance)
        assertEquals(listOf(HealthPlan.Amount("$p-kcal", 0, 600_000, 40.0)), h.kcal)
    }

    @Test fun aWalkShorterThanASecondStillGetsAValidSpan() {
        val h = HealthPlan.of(p, 0, 400, emptyList(), steps = 1, distanceM = 0.0, kcal = 0.0)
        assertEquals(1_000L, h.session.endMs); assertEquals(1_000L, h.steps.single().endMs)
        assertTrue(h.distance.isEmpty()); assertTrue(h.kcal.isEmpty())
    }

    @Test fun pausesSplitTheRecordsIntoTheMovingStretches() {
        // moving 0-100 s, paused 100-160 s, moving 160-460 s, paused 460-500 s, moving 500-600 s
        val pauses = listOf(100_000L to 160_000L, 460_000L to 500_000L)
        val h = HealthPlan.of(p, 0, 600_000, pauses, steps = 1000, distanceM = 500.0, kcal = 40.0)
        assertEquals(pauses, h.session.pauses)
        assertEquals(listOf(0L to 100_000L, 160_000L to 460_000L, 500_000L to 600_000L), h.steps.map { it.startMs to it.endMs })
        assertEquals(listOf("$p-steps-0", "$p-steps-1", "$p-steps-2"), h.steps.map { it.id })
        assertEquals(listOf(200.0, 600.0, 200.0), h.steps.map { it.value })
        assertEquals(500.0, h.distance.sumOf { it.value }, 1e-9); assertEquals(40.0, h.kcal.sumOf { it.value }, 1e-9)
        assertEquals(listOf("$p-kcal-0", "$p-kcal-1", "$p-kcal-2"), h.kcal.map { it.id })
    }

    @Test fun stepSharesAreWholeNeverZeroAndAddUpExactly() {
        // three stretches of 1 s, 1 s and 998 s: the tiny ones still get a step so the watch cannot fill moving time
        val pauses = listOf(1_000L to 2_000L, 3_000L to 4_000L)
        val h = HealthPlan.of(p, 0, 1_002_000, pauses, steps = 101, distanceM = 0.0, kcal = 0.0)
        assertEquals(101.0, h.steps.sumOf { it.value })
        assertTrue(h.steps.all { it.value >= 1.0 && it.value == Math.floor(it.value) })
        assertEquals(3, h.steps.size)
    }

    @Test fun fewerStepsThanStretchesGoToTheLongestStretches() {
        val pauses = listOf(10_000L to 20_000L, 25_000L to 30_000L)          // stretches 10 s, 5 s, 70 s
        val h = HealthPlan.of(p, 0, 100_000, pauses, steps = 2, distanceM = 0.0, kcal = 0.0)
        assertEquals(listOf("$p-steps-0" to 1.0, "$p-steps-2" to 1.0), h.steps.map { it.id to it.value })
    }

    @Test fun pausesAtTheVeryStartAndEndLeaveOneStretch() {
        val h = HealthPlan.of(p, 0, 100_000, listOf(0L to 10_000L, 90_000L to 100_000L), steps = 80, distanceM = 0.0, kcal = 0.0)
        assertEquals(listOf(HealthPlan.Amount("$p-steps-0", 10_000, 90_000, 80.0)), h.steps)
    }

    @Test fun theSameWalkAlwaysGetsTheSameIds() {
        val pauses = listOf(100_000L to 160_000L)
        assertEquals(HealthPlan.of(p, 0, 600_000, pauses, 1000, 500.0, 40.0), HealthPlan.of(p, 0, 600_000, pauses, 1000, 500.0, 40.0))
    }
}
