// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionEntity::class, ProfileEntity::class], version = 2)
abstract class AppDb : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun profile(): ProfileDao

    companion object {
        /** 0.1.9: walks remember their pauses so Health Connect gets steps only for the time the belt moved. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE sessions ADD COLUMN pauses TEXT NOT NULL DEFAULT ''") }
        }

        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "underfoot.db").addMigrations(MIGRATION_1_2).build().also { inst = it }
        }
    }
}
