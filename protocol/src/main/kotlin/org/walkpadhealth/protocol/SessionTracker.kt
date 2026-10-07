package org.walkpadhealth.protocol

data class Progress(val activeSec: Long, val distanceM: Double)

/** Pure state machine. Not thread-safe: the caller must serialize all calls on one thread. Times are milliseconds on a monotonic clock the caller supplies (the service uses `SystemClock.elapsedRealtime()`); the caller converts summary times to epoch for storage. */
class SessionTracker(
    private val gapMs: Long = 5_000,
    private val disconnectMs: Long = 60_000,
    private val minActiveSec: Long = 10,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
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

    val isActive: Boolean get() = startMs != null

    fun progress() = Progress(activeMs / 1000, integratedM)

    fun onTelemetry(t: Telemetry, nowMs: Long): SessionSummary? {
        val expired = tick(nowMs)            // an expired disconnect ends the old session before this frame is considered
        disconnectedAt = null
        val open = startMs != null
        if (open) {
            val dt = nowMs - lastMs
            if (lastStatus == BeltStatus.RUNNING && t.status == BeltStatus.RUNNING && dt in 1..gapMs) {
                activeMs += dt
                lastSpeedKmh?.let { integratedM += it / 3.6 * dt / 1000.0 }   // previous speed held over the interval
            }
            latest = Totals(t.distanceM ?: latest.dist, t.steps ?: latest.steps, t.kcal ?: latest.kcal)
        }
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
        if (startMs != null && disconnectedAt == null) disconnectedAt = nowMs
    }

    fun tick(nowMs: Long): SessionSummary? {
        val d = disconnectedAt ?: return null
        return if (nowMs - d >= disconnectMs) end(d) else null
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
        startMs = null; disconnectedAt = null; activeMs = 0; integratedM = 0.0
        base = Totals(); latest = Totals()
        return if (summary.activeSec >= minActiveSec) summary else null
    }
}
