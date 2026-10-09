package org.underfoot.protocol

data class Progress(val activeSec: Long, val distanceM: Double, val steps: Int? = null)

/** Pure state machine. Not thread-safe: the caller must serialize all calls on one thread. Times are milliseconds on a monotonic clock the caller supplies (the service uses `SystemClock.elapsedRealtime()`); the caller converts summary times to epoch for storage. */
class SessionTracker(
    private val gapMs: Long = 5_000,
    private val disconnectMs: Long = 60_000,
    private val minActiveSec: Long = 10,
    /**
     * A pause this long ends the walk (dated to when the pause began). Long-pressing play/pause on the URTM059 ("End") is reported as
     * STOPPED, so this is only a backstop for a pad left paused and connected; the pad powers itself off after a while, which ends the
     * walk through the disconnect timeout instead.
     */
    private val pauseEndMs: Long = 30 * 60_000,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private companion object { const val RELEASE_GRACE_MS = 2 * 60_000L }

    private data class Totals(val dist: Double? = null, val steps: Int? = null, val kcal: Double? = null)

    private var startMs: Long? = null
    private var wallStartMs = 0L
    private var lastMs = 0L
    private var lastStatus = BeltStatus.IDLE
    private var lastSpeedKmh: Double? = null
    private var activeMs = 0L
    private var integratedM = 0.0
    private var base = Totals()
    private var latest = Totals()
    private var disconnectedAt: Long? = null
    private var pausedSince: Long? = null
    private var released = false                   // the app dropped the link on purpose (see onRelease); cleared by the next frame
    private var releaseDeadline = 0L
    private var lastElapsed: Int? = null           // pad counters from the latest frame, used to fill the time spent unobserved
    private var lastDist: Double? = null
    private var gapDistFrom: Double? = null        // distance at the start of a gap whose distance is not filled yet (FTMS arrives after fff1)
    private var unobserved = false                 // a link loss since the last frame: the interval across it is not walking time

    val isActive: Boolean get() = startMs != null

    fun progress() = Progress(
        activeMs / 1000, integratedM,
        if (startMs != null) delta(base.steps?.toDouble(), latest.steps?.toDouble())?.toInt() else null,
    )

    fun onTelemetry(t: Telemetry, nowMs: Long): SessionSummary? {
        // An expired disconnect ends the old session before this frame is considered; a pad that is moving again is not an expired pause.
        val expired = expire(nowMs, pauseMayExpire = t.status != BeltStatus.RUNNING && t.status != BeltStatus.STARTING)
        disconnectedAt = null; released = false
        val gap = unobserved; unobserved = false
        val open = startMs != null
        if (open) {
            if (gap) {                       // time the app did not see: take it from the pad's own counters
                val e0 = lastElapsed; val e1 = t.elapsedSec
                if (e0 != null && e1 != null && e1 > e0) activeMs += (e1 - e0) * 1000L
                gapDistFrom = lastDist
            }
            val d1 = t.distanceM; val d0 = gapDistFrom
            if (d0 != null && d1 != null) { if (d1 > d0) integratedM += d1 - d0; gapDistFrom = null }
            val dt = nowMs - lastMs
            if (!gap && lastStatus == BeltStatus.RUNNING && t.status == BeltStatus.RUNNING && dt in 1..gapMs) {
                activeMs += dt
                lastSpeedKmh?.let { integratedM += it / 3.6 * dt / 1000.0 }   // previous speed held over the interval
            }
            latest = Totals(t.distanceM ?: latest.dist, t.steps ?: latest.steps, t.kcal ?: latest.kcal)
            when (t.status) {
                BeltStatus.PAUSING, BeltStatus.PAUSED -> if (pausedSince == null) pausedSince = nowMs
                BeltStatus.RUNNING, BeltStatus.STARTING -> pausedSince = null
                else -> {}
            }
        }
        t.elapsedSec?.let { lastElapsed = it }; t.distanceM?.let { lastDist = it }
        lastMs = nowMs
        lastStatus = t.status
        lastSpeedKmh = t.speedKmh
        val ended = when {
            !open && t.status == BeltStatus.RUNNING -> { begin(nowMs, t); null }
            open && (t.status == BeltStatus.STOPPED || t.status == BeltStatus.IDLE) -> end(nowMs)
            else -> null
        }
        return ended ?: expired
    }

    fun onDisconnect(nowMs: Long) {
        if (released) return                       // the planned disconnect that follows onRelease
        if (startMs != null) unobserved = true
        if (startMs != null && disconnectedAt == null) disconnectedAt = nowMs
    }

    /** The app drops the link on purpose so the pad can sleep: unlike a lost link this does not start the disconnect timeout. */
    fun onRelease(nowMs: Long, holdoffMs: Long) {
        if (startMs != null) { unobserved = true; released = true; releaseDeadline = nowMs + holdoffMs + RELEASE_GRACE_MS }
    }

    fun tick(nowMs: Long): SessionSummary? = expire(nowMs, pauseMayExpire = true)

    private fun expire(nowMs: Long, pauseMayExpire: Boolean): SessionSummary? {
        val d = disconnectedAt
        if (d != null && nowMs - d >= disconnectMs) return end(d)
        val p = pausedSince
        if (pauseMayExpire && p != null) {
            val limit = if (released) maxOf(p + pauseEndMs, releaseDeadline) else p + pauseEndMs
            if (nowMs >= limit) return end(p)
        }
        return null
    }

    fun finish(nowMs: Long): SessionSummary? = if (startMs != null) end(nowMs) else null

    private fun begin(nowMs: Long, t: Telemetry) {
        startMs = nowMs; wallStartMs = wallClock(); activeMs = 0; integratedM = 0.0
        base = Totals(t.distanceM, t.steps, t.kcal); latest = base
    }

    private fun delta(b: Double?, l: Double?): Double? = when {
        l == null || !l.isFinite() -> null
        b != null && b.isFinite() && l >= b -> l - b
        else -> l                                  // counter reset: the latest value is the session value
    }

    private fun end(endMs: Long): SessionSummary? {
        val s = startMs ?: return null
        val summary = SessionSummary(
            s, endMs, activeMs / 1000, integratedM,
            delta(base.dist, latest.dist),
            delta(base.steps?.toDouble(), latest.steps?.toDouble())?.toInt(),
            delta(base.kcal, latest.kcal),
            wallStartMs,
        )
        startMs = null; disconnectedAt = null; released = false; lastElapsed = null; lastDist = null; gapDistFrom = null; unobserved = false; pausedSince = null; activeMs = 0; integratedM = 0.0
        base = Totals(); latest = Totals()
        return if (summary.activeSec >= minActiveSec) summary else null
    }
}
