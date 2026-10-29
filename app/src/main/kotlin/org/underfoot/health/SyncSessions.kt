// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.health

import kotlinx.coroutines.CancellationException
import org.underfoot.data.SessionDao
import org.underfoot.data.SessionEntity

interface HealthGateway {
    suspend fun hasPermissions(): Boolean
    suspend fun write(s: SessionEntity)
}

enum class SyncResult { Done, Blocked, Retry }

/** `warn` reports a walk Health Connect can never take (production logs it; tests collect it). */
class SyncSessions(private val dao: SessionDao, private val gw: HealthGateway, private val warn: (String) -> Unit = {}) {
    suspend fun run(): SyncResult {
        try { if (!gw.hasPermissions()) return SyncResult.Blocked }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return SyncResult.Retry }
        for (s in dao.unsynced()) {
            try { gw.write(s); dao.markSynced(s.id) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { warn("skipped walk ${s.id}: ${e.message}"); continue }   // permanently invalid: skip it, do not block later walks
            catch (e: Exception) { return SyncResult.Retry }
        }
        return SyncResult.Done
    }
}
