package org.underfoot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.underfoot.data.FlushResult
import org.underfoot.data.ProfileDao
import org.underfoot.data.ProfileEntity
import org.underfoot.data.SessionDao
import org.underfoot.data.SessionEntity
import org.underfoot.data.SessionStore
import org.underfoot.protocol.Estimators
import org.underfoot.protocol.Profile
import org.underfoot.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStoreTest {
    private class Sessions(val failOnCalls: Set<Int> = emptySet()) : SessionDao {
        val rows = mutableListOf<SessionEntity>()
        private var calls = 0
        override suspend fun insert(s: SessionEntity): Long {
            if (++calls in failOnCalls) throw IllegalStateException("disk full")
            rows += s.copy(id = rows.size + 1L); return rows.size.toLong()
        }
        override fun observeAll(): Flow<List<SessionEntity>> = flowOf(rows)
        override suspend fun unsynced() = rows.toList()
        override suspend fun markSynced(id: Long) {}
    }

    private class Profiles(var p: ProfileEntity?) : ProfileDao {
        override fun observe(): Flow<ProfileEntity?> = flowOf(p)
        override suspend fun get() = p
        override suspend fun upsert(p: ProfileEntity) { this.p = p }
    }

    /** A summary as the tracker emits it: monotonic start/end, plus the wall-clock start captured when the walk began. */
    private fun walk(monoStart: Long, wallStart: Long) =
        SessionSummary(startMs = monoStart, endMs = monoStart + 60_000, activeSec = 60, integratedDistanceM = 3000.0,
            deviceDistanceM = null, deviceSteps = null, deviceKcal = null, wallStartMs = wallStart)

    @Test fun savesWithTheProfileStoredAtSaveTime() = runTest {
        val sessions = Sessions(); val profiles = Profiles(null)
        val store = SessionStore(sessions, profiles)
        profiles.p = ProfileEntity(weightKg = 80.0, heightCm = 180.0)             // set after the store was built, before the flush
        store.enqueue(walk(5_000, 1_700_000_000_000)); assertEquals(FlushResult.SAVED, store.flush())
        assertEquals(Estimators.kcal(3000.0, 60.0, 80.0), sessions.rows.single().kcal, 1e-9)
        assertEquals(Estimators.steps(3000.0, 180.0), sessions.rows.single().steps)
    }

    @Test fun usesTheDefaultProfileWhenNoneIsSet() = runTest {
        val sessions = Sessions(); val store = SessionStore(sessions, Profiles(null))
        store.enqueue(walk(0, 1_000)); store.flush()
        assertEquals(Estimators.kcal(3000.0, 60.0, Profile.DEFAULT.weightKg), sessions.rows.single().kcal, 1e-9)
    }

    @Test fun storedTimesAreWallClockWithTheMonotonicDuration() = runTest {
        val sessions = Sessions(); val store = SessionStore(sessions, Profiles(null))
        store.enqueue(walk(monoStart = 5_000, wallStart = 1_700_000_000_000)); store.flush()
        assertEquals(1_700_000_000_000L, sessions.rows.single().startMs)
        assertEquals(1_700_000_060_000L, sessions.rows.single().endMs)
    }

    @Test fun aFailedInsertKeepsTheSessionAndTheOrder() = runTest {
        val sessions = Sessions(failOnCalls = setOf(1)); val store = SessionStore(sessions, Profiles(null))
        store.enqueue(walk(0, 1_000)); store.enqueue(walk(100_000, 2_000))
        assertEquals(FlushResult.FAILED, store.flush()); assertEquals(0, sessions.rows.size)
        assertEquals(FlushResult.SAVED, store.flush())
        assertEquals(listOf(1_000L, 2_000L), sessions.rows.map { it.startMs })
    }

    @Test fun aSessionIsNeverInsertedTwiceAfterAPartialFailure() = runTest {
        val sessions = Sessions(failOnCalls = setOf(2)); val store = SessionStore(sessions, Profiles(null))
        store.enqueue(walk(0, 1_000)); store.enqueue(walk(100_000, 2_000))
        assertEquals(FlushResult.FAILED, store.flush()); assertEquals(1, sessions.rows.size)
        assertEquals(FlushResult.SAVED, store.flush())
        assertEquals(listOf(1_000L, 2_000L), sessions.rows.map { it.startMs })
    }

    @Test fun nothingQueuedIsNothingToDo() = runTest {
        val store = SessionStore(Sessions(), Profiles(null))
        assertEquals(FlushResult.NOTHING, store.flush())
        assertEquals(false, store.hasPending)
    }
}
