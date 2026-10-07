package org.walkpadhealth.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class, ProfileEntity::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun profile(): ProfileDao

    companion object {
        @Volatile private var inst: AppDb? = null
        /** Last saved profile, so the service can show live estimates without a DB read. */
        @Volatile var cachedProfile: org.walkpadhealth.protocol.Profile? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "walkpad.db").build().also { inst = it }
        }
    }
}
