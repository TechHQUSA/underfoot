// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.protocol

data class Progress(val activeSec: Long, val distanceM: Double, val steps: Int? = null)

/** The pad's counters where the app (not the pad) ended a walk, and the wall time it did so. The caller keeps it across restarts. */
data class Carry(val distanceM: Double?, val steps: Int?, val kcal: Double?, val elapsedSec: Int?, val atWallMs: Long) {
    /** One line for storage; nulls are empty fields. */
    fun encode(): String = listOf(distanceM ?: "", steps ?: "", kcal ?: "", elapsedSec ?: "", atWallMs).joinToString("|")

    companion object {
        fun decode(s: String): Carry? = s.split('|').takeIf { it.size == 5 }?.let {
            Carry(it[0].toDoubleOrNull(), it[1].toIntOrNull(), it[2].toDoubleOrNull(), it[3].toIntOrNull(), it[4].toLongOrNull() ?: return null)
        }
    }
}

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
    /** A carry older than this is ignored: the pad has long since ended or slept, and a walk after that starts its counters over. */
    private val carryMaxMs: Long = 2 * 60 * 60_000,
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
    private var pausedSince: Long? = null
    private var released = false                   // the app dropped the link on purpose (see onRelease); cleared when the link is back
    private var lastElapsed: Int? = null           // pad counters from the latest frame, used to fill the time spent unobserved
    private var lastDist: Double? = null
    private var gapDistFrom: Double? = null        // distance at the start of a gap whose distance is not filled yet (FTMS arrives after fff1)
    private var unobserved = false                 // a link loss since the last frame: the interval across it is not walking time
    private var pauseOpenAt: Long? = null          // the belt stopped here and has not run since; unlike pausedSince, the countdown keeps it open
    private val pauses = mutableListOf<Pair<Long, Long>>()

    private var pending: Carry? = null             // where the app last ended a walk the pad may still be on
    private var carriedFrom: Carry? = null         // the carry the open walk counts from, kept again if that walk is too short to save

    /**
     * Where the counting of the pad's current walk starts. Read it after each call and restore it into a new tracker: while a walk
     * counts on from a saved one, this stays that walk's carry, so a process kill mid-walk does not count the saved part again.
     */
    var carry: Carry?
        get() = pending ?: carriedFrom
        set(v) { pending = v }

    val isActive: Boolean get() = startMs != null

    fun progress() = Progress(
        activeMs / 1000, integratedM,
        if (startMs != null) delta(base.steps?.toDouble(), latest.steps?.toDouble())?.toInt() else null,
    )

    fun onTelemetry(t: Telemetry, nowMs: Long): SessionSummary? {
        // An expired disconnect ends the old session before this frame is considered; a pad that is moving again is not an expired pause.
        val expired = expire(nowMs, pauseMayExpire = t.status != BeltStatus.RUNNING && t.status != BeltStatus.STARTING)
        disconnectedAt = null
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
                BeltStatus.PAUSING, BeltStatus.PAUSED -> { if (pausedSince == null) pausedSince = nowMs; if (pauseOpenAt == null) pauseOpenAt = nowMs }
                BeltStatus.RUNNING, BeltStatus.STARTING -> pausedSince = null
                else -> {}
            }
            if (t.status == BeltStatus.RUNNING) pauseOpenAt?.let { if (nowMs > it) pauses += it to nowMs; pauseOpenAt = null }
        }
        if (t.status == BeltStatus.STOPPED || t.status == BeltStatus.IDLE) pending = null   // the pad's counters start over
        t.elapsedSec?.let { lastElapsed = it }; t.distanceM?.let { lastDist = it }
        lastMs = nowMs
        lastStatus = t.status
        lastSpeedKmh = t.speedKmh
        val ended = when {
            !open && t.status == BeltStatus.RUNNING -> { begin(nowMs, t); null }
            open && (t.status == BeltStatus.STOPPED || t.status == BeltStatus.IDLE) -> end(nowMs, padEnded = true)
            else -> null
        }
        return ended ?: expired
    }

    fun onDisconnect(nowMs: Long) {
        if (released) { if (startMs != null) unobserved = true; return }    // the planned disconnect that follows onRelease
        if (startMs != null) unobserved = true
        if (startMs != null && disconnectedAt == null) disconnectedAt = nowMs
    }

    /** The app drops the link on purpose so the pad can sleep: unlike a lost link this does not start the disconnect timeout. A paused walk still ends at the usual pause backstop. */
    fun onRelease() { if (startMs != null) { unobserved = true; released = true } }

    /** The link is back (the user asked for it): an unplanned loss times out again. `unobserved` stays until the first frame fills the gap. */
    fun onReconnected() { released = false }

    fun tick(nowMs: Long): SessionSummary? = expire(nowMs, pauseMayExpire = true)

    private fun expire(nowMs: Long, pauseMayExpire: Boolean): SessionSummary? {
        val d = disconnectedAt
        if (d != null && nowMs - d >= disconnectMs) return end(d)
        val p = pausedSince
        if (pauseMayExpire && p != null) {
            if (nowMs - p >= pauseEndMs) return end(p)
        }
        return null
    }

    fun finish(nowMs: Long): SessionSummary? = if (startMs != null) end(nowMs) else null

    /**
     * The pad's counters belong to its walk and reset when it starts, so a walk the app joins late (started on the remote) counts from
     * zero: the steps, time and distance walked before the app connected are kept. Only a walk the app itself ended while the pad went
     * on (pause backstop, disconnect timeout) counts from where that saved walk stopped, so nothing is saved twice. The time before the
     * join comes from the pad's elapsed counter, which stands still in a pause, so the start is backdated by at least that much.
     */
    private fun begin(nowMs: Long, t: Telemetry) {
        val c = pending?.takeIf { wallClock() - it.atWallMs in 0..carryMaxMs }; val e = t.elapsedSec
        val goesOn = c?.takeIf { e == null || it.elapsedSec == null || e >= it.elapsedSec }   // lower counters: the pad started a new walk
        base = if (goesOn != null) Totals(goesOn.distanceM, goesOn.steps, goesOn.kcal) else Totals()
        val fromSec = if (goesOn != null) goesOn.elapsedSec else 0
        activeMs = if (e != null && fromSec != null && e > fromSec) (e - fromSec) * 1000L else 0
        val d0 = if (goesOn != null) goesOn.distanceM else 0.0; val d1 = t.distanceM
        integratedM = if (d0 != null && d1 != null && d1 > d0) d1 - d0 else 0.0
        gapDistFrom = if (d1 == null) d0 else null           // otherwise filled from the first frame that carries distance (FTMS arrives after fff1)
        startMs = nowMs - activeMs; wallStartMs = wallClock() - activeMs
        latest = Totals(t.distanceM ?: base.dist, t.steps ?: base.steps, t.kcal ?: base.kcal)
        carriedFrom = goesOn; pending = null
        pauses.clear(); pauseOpenAt = null
    }

    private fun delta(b: Double?, l: Double?): Double? = when {
        l == null || !l.isFinite() -> null
        b != null && b.isFinite() && l >= b -> l - b
        else -> l                                  // counter reset: the latest value is the session value
    }

    private fun end(endMs: Long, padEnded: Boolean = false): SessionSummary? {
        val s = startMs ?: return null
        pauseOpenAt?.let { if (it < endMs) pauses += it to endMs }       // a plain pause backstop ends exactly where its pause began
        val summary = SessionSummary(
            s, endMs, activeMs / 1000, integratedM,
            delta(base.dist, latest.dist),
            delta(base.steps?.toDouble(), latest.steps?.toDouble())?.toInt(),
            delta(base.kcal, latest.kcal),
            wallStartMs,
            pauses.map { (a, b) -> maxOf(a, s) to minOf(b, endMs) }.filter { (a, b) -> b > a },
        )
        pauses.clear(); pauseOpenAt = null
        val endElapsed = lastElapsed
        startMs = null; disconnectedAt = null; released = false; lastElapsed = null; lastDist = null; gapDistFrom = null; unobserved = false; pausedSince = null; activeMs = 0; integratedM = 0.0
        val kept = summary.activeSec >= minActiveSec
        pending = when { padEnded -> null; kept -> Carry(latest.dist, latest.steps, latest.kcal, endElapsed, wallClock()); else -> carriedFrom }
        base = Totals(); latest = Totals(); carriedFrom = null
        return if (kept) summary else null
    }
}
