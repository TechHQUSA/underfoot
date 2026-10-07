package org.underfoot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.underfoot.data.SessionDao
import org.underfoot.data.SessionEntity
import org.underfoot.health.HealthGateway
import org.underfoot.health.SyncResult
import org.underfoot.health.SyncSessions
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSessionsTest {
    private class FakeDao(val rows: MutableList<SessionEntity>) : SessionDao {
        val synced = mutableListOf<Long>()
        override suspend fun insert(s: SessionEntity) = 0L
        override fun observeAll(): Flow<List<SessionEntity>> = flowOf(rows)
        override suspend fun unsynced() = rows.filter { it.id !in synced }
        override suspend fun markSynced(id: Long) { synced += id }
    }
    private class FakeGw(var perms: Boolean = true, var failOn: Long? = null, var rejectId: Long? = null) : HealthGateway {
        val written = mutableListOf<Long>()
        override suspend fun hasPermissions() = perms
        override suspend fun write(s: SessionEntity) {
            if (s.id == rejectId) throw IllegalArgumentException("value out of range")
            if (s.id == failOn) error("boom")
            written += s.id
        }
    }
    private fun row(id: Long) = SessionEntity(id, id, id + 1, 60, 50.0, "ESTIMATED", 70, "ESTIMATED", 3.0, "ESTIMATED")

    @Test fun writesAndMarksEveryUnsyncedSession() = runTest {
        val dao = FakeDao(mutableListOf(row(1), row(2))); val gw = FakeGw()
        assertEquals(SyncResult.Done, SyncSessions(dao, gw).run())
        assertEquals(listOf(1L, 2L), gw.written); assertEquals(listOf(1L, 2L), dao.synced)
    }

    @Test fun permissionDeniedWritesNothingAndReportsBlocked() = runTest {
        val dao = FakeDao(mutableListOf(row(1))); val gw = FakeGw(perms = false)
        assertEquals(SyncResult.Blocked, SyncSessions(dao, gw).run())
        assertEquals(emptyList(), gw.written); assertEquals(emptyList(), dao.synced)
    }

    @Test fun failedWriteIsNotMarkedSyncedAndAsksForRetry() = runTest {
        val dao = FakeDao(mutableListOf(row(1), row(2), row(3))); val gw = FakeGw(failOn = 2)
        assertEquals(SyncResult.Retry, SyncSessions(dao, gw).run())
        assertEquals(listOf(1L), dao.synced)                       // 2 failed, 3 not attempted
    }

    @Test fun rerunAfterFailureOnlyWritesWhatIsLeft() = runTest {
        val dao = FakeDao(mutableListOf(row(1), row(2))); val gw = FakeGw(failOn = 2)
        SyncSessions(dao, gw).run()
        gw.failOn = null
        assertEquals(SyncResult.Done, SyncSessions(dao, gw).run())
        assertEquals(listOf(1L, 2L), dao.synced); assertEquals(listOf(1L, 2L), gw.written)
    }

    @Test fun invalidSessionIsSkippedAndDoesNotBlockLaterOnes() = runTest {
        val dao = FakeDao(mutableListOf(row(1), row(2), row(3))); val gw = FakeGw(rejectId = 2)
        assertEquals(SyncResult.Done, SyncSessions(dao, gw).run())
        assertEquals(listOf(1L, 3L), dao.synced)                   // 2 is permanently invalid: left unsynced, not retried forever
    }

    @Test fun nothingToSyncIsDone() = runTest {
        assertEquals(SyncResult.Done, SyncSessions(FakeDao(mutableListOf()), FakeGw()).run())
    }
}
