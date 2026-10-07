package org.walkpadhealth.health

import kotlinx.coroutines.CancellationException
import org.walkpadhealth.data.SessionDao
import org.walkpadhealth.data.SessionEntity

interface HealthGateway {
    suspend fun hasPermissions(): Boolean
    suspend fun write(s: SessionEntity)
}

enum class SyncResult { Done, Blocked, Retry }

class SyncSessions(private val dao: SessionDao, private val gw: HealthGateway) {
    suspend fun run(): SyncResult {
        if (!gw.hasPermissions()) return SyncResult.Blocked
        for (s in dao.unsynced()) {
            try { gw.write(s); dao.markSynced(s.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return SyncResult.Retry }
        }
        return SyncResult.Done
    }
}
