package org.underfoot.protocol

/**
 * Decides when the app should let go of the pad so the pad can switch itself off (it stays on while anything is connected), and
 * owns the hold-off during which the app must not reconnect. Times are milliseconds on the caller's monotonic clock.
 * `minutes` = 0 disables it. Not thread-safe.
 */
class SleepGate(var minutes: Int = 0, val holdoffMs: Long = 15 * 60_000L) {
    var holdoffUntil = 0L
        private set
    private var quietSince: Long? = null

    /** Call about once a second. Returns true when the caller should disconnect now; the hold-off starts at that moment. */
    fun onTick(connected: Boolean, status: BeltStatus, nowMs: Long): Boolean {
        val quiet = connected && status != BeltStatus.RUNNING && status != BeltStatus.STARTING
        if (!quiet) { quietSince = null; return false }
        val since = quietSince ?: nowMs.also { quietSince = it }
        if (minutes <= 0 || nowMs - since < minutes * 60_000L) return false
        quietSince = null
        holdoffUntil = nowMs + holdoffMs
        return true
    }

    /** Ends the hold-off early (Reconnect now, or the belt started moving again). */
    fun cancel() { holdoffUntil = 0; quietSince = null }

    fun waitMs(nowMs: Long): Long = (holdoffUntil - nowMs).coerceAtLeast(0)
}
