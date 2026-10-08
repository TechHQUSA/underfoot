package org.underfoot.health

import kotlinx.coroutines.CancellationException
import org.underfoot.data.SessionDao
import org.underfoot.data.SessionEntity

interface HealthGateway {
    suspend fun hasPermissions(): Boolean
    suspend fun write(s: SessionEntity)
}

enum class SyncResult { Done, Blocked, Retry }

class SyncSessions(private val dao: SessionDao, private val gw: HealthGateway) {
    suspend fun run(): SyncResult {
        try { if (!gw.hasPermissions()) return SyncResult.Blocked }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return SyncResult.Retry }
        for (s in dao.unsynced()) {
            try { gw.write(s); dao.markSynced(s.id) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { continue }   // permanently invalid for Health Connect: skip it, do not block later walks
            catch (e: Exception) { return SyncResult.Retry }
        }
        return SyncResult.Done
    }
}
