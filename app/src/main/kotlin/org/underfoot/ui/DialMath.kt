package org.underfoot.ui

import kotlin.math.atan2

/** Maps a touch on the speed dial to 0..1 along its 270 degree arc, which starts at 135 degrees (bottom left) and runs clockwise. */
object DialMath {
    const val START_DEG = 135f
    const val SWEEP_DEG = 270f

    /** dx and dy are the touch offset from the dial centre in screen coordinates (y grows downward). */
    fun fractionAtOrNull(dx: Double, dy: Double): Float? {
        if (dx == 0.0 && dy == 0.0) return null
        val deg = Math.toDegrees(atan2(dy, dx)).let { if (it < 0) it + 360 else it }
        val rel = (deg - START_DEG + 360) % 360
        return when {
            rel <= SWEEP_DEG -> (rel / SWEEP_DEG).toFloat()
            rel < SWEEP_DEG + (360 - SWEEP_DEG) / 2 -> 1f      // the gap: snap to the nearer end
            else -> 0f
        }
    }

    fun fractionAt(dx: Double, dy: Double): Float = fractionAtOrNull(dx, dy) ?: 0f
}
