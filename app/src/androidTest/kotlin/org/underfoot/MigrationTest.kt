// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.underfoot.data.AppDb
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDb::class.java)

    @Test fun walksSavedBefore019KeepEverythingAndHaveNoPauses() {
        helper.createDatabase("m", 1).use {
            it.execSQL("INSERT INTO sessions (startMs, endMs, activeSec, distanceM, distanceSource, steps, stepsSource, kcal, kcalSource, synced) " +
                "VALUES (1000, 61000, 60, 50.0, 'DEVICE', 70, 'DEVICE', 3.0, 'ESTIMATED', 1)")
        }
        // validates the migrated schema against 2.json, including the column default
        helper.runMigrationsAndValidate("m", 2, true, AppDb.MIGRATION_1_2).use { db ->
            db.query("SELECT steps, synced, pauses FROM sessions").use { c ->
                c.moveToFirst()
                assertEquals(70, c.getInt(0)); assertEquals(1, c.getInt(1)); assertEquals("", c.getString(2))
            }
        }
    }
}
