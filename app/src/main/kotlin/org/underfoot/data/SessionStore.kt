package org.underfoot.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.underfoot.protocol.Profile
import org.underfoot.protocol.SessionSummary
import org.underfoot.protocol.finalize
import org.underfoot.protocol.withWallClock
import java.util.concurrent.ConcurrentLinkedQueue

enum class FlushResult { NOTHING, SAVED, FAILED }

/**
 * Where a finished walk goes. Hand it the tracker's summary; it converts the times, applies the profile that is stored at save
 * time (never a cached copy), and inserts in order. A failed insert (for example storage full) leaves that walk and the later ones
 * queued, so the next flush retries them without duplicating any. A process death while storage is full loses the queue; that
 * cannot be avoided without storage.
 */
class SessionStore(private val sessions: SessionDao, private val profiles: ProfileDao) {
    private val pending = ConcurrentLinkedQueue<SessionSummary>()
    private val lock = Mutex()

    val hasPending: Boolean get() = pending.isNotEmpty()

    fun enqueue(s: SessionSummary) { pending.add(s.withWallClock()) }

    suspend fun flush(): FlushResult = lock.withLock {
        var saved = false
        while (true) {
            val next = pending.peek() ?: break
            val profile = profiles.get()?.toProfile() ?: Profile.DEFAULT
            try {
                sessions.insert(next.finalize(profile).toEntity())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock FlushResult.FAILED
            }
            pending.poll(); saved = true
        }
        if (saved) FlushResult.SAVED else FlushResult.NOTHING
    }
}
