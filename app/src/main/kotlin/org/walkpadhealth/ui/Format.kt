package org.walkpadhealth.ui

import java.util.Locale

fun fmtDuration(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

fun fmtKm(m: Double): String = String.format(Locale.getDefault(), "%.2f", if (m.isFinite() && m > 0) m / 1000.0 else 0.0)
