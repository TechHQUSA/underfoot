package org.underfoot.protocol

/**
 * Decides when the app should let go of the pad so the pad can switch itself off (it stays on while anything is connected, and a
 * connection attempt wakes it again). After a release the app stays away until [cancel] is called by an explicit user action.
 * Times are milliseconds on the caller's monotonic clock. `minutes` = 0 disables it. Not thread-safe.
 */
class SleepGate(var minutes: Int = 0) {
    /** True from the release until [cancel]; while true the caller must not look for or connect to the pad. */
    var resting = false
        private set
    private var quietSince: Long? = null

    /** Call about once a second. Returns true when the caller should disconnect now. */
    fun onTick(connected: Boolean, status: BeltStatus, nowMs: Long): Boolean {
        val quiet = connected && status != BeltStatus.RUNNING && status != BeltStatus.STARTING
        if (!quiet) { quietSince = null; return false }
        val since = quietSince ?: nowMs.also { quietSince = it }
        if (minutes <= 0 || nowMs - since < minutes * 60_000L) return false
        quietSince = null
        resting = true
        return true
    }

    /** Ends the rest (Reconnect now, or the belt started moving again). */
    fun cancel() { resting = false; quietSince = null }
}
