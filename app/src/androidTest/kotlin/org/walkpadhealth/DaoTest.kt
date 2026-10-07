package org.walkpadhealth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.walkpadhealth.data.AppDb
import org.walkpadhealth.data.ProfileEntity
import org.walkpadhealth.data.SessionEntity
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDb
    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun s(start: Long) = SessionEntity(startMs = start, endMs = start + 1, activeSec = 60, distanceM = 50.0,
        distanceSource = "ESTIMATED", steps = 70, stepsSource = "ESTIMATED", kcal = 3.0, kcalSource = "ESTIMATED")

    @Test fun unsyncedReturnsOnlyUnsyncedInOrderAndMarkSyncedRemovesOne() = runBlocking {
        val a = db.sessions().insert(s(2)); db.sessions().insert(s(1))
        assertEquals(listOf(1L, 2L), db.sessions().unsynced().map { it.startMs })
        db.sessions().markSynced(a)
        assertEquals(listOf(1L), db.sessions().unsynced().map { it.startMs })
    }

    @Test fun profileUpsertReplacesSingleRow() = runBlocking {
        assertNull(db.profile().get())
        db.profile().upsert(ProfileEntity(weightKg = 70.0, heightCm = 170.0))
        db.profile().upsert(ProfileEntity(weightKg = 80.0, heightCm = 180.0))
        assertEquals(80.0, db.profile().get()!!.weightKg)
    }
}
