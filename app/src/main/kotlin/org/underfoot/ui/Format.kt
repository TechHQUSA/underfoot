package org.underfoot.ui

import java.util.Locale

private const val KM_PER_MILE = 1.609344
private const val KG_PER_LB = 0.45359237
private const val CM_PER_IN = 2.54

fun fmtDuration(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

fun fmtKm(m: Double): String = String.format(Locale.getDefault(), "%.2f", if (m.isFinite() && m > 0) m / 1000.0 else 0.0)

/** Distance in miles (imperial) or kilometres, two decimals. Input is metres. */
fun fmtDistance(m: Double, imperial: Boolean): String {
    val km = if (m.isFinite() && m > 0) m / 1000.0 else 0.0
    return String.format(Locale.getDefault(), "%.2f", if (imperial) km / KM_PER_MILE else km)
}

/** Speed in mph (imperial) or km/h, one decimal. Input is km/h. */
fun fmtSpeed(kmh: Double, imperial: Boolean): String {
    val v = if (kmh.isFinite() && kmh > 0) kmh else 0.0
    return String.format(Locale.getDefault(), "%.1f", if (imperial) v / KM_PER_MILE else v)
}

fun kgToLb(kg: Double) = kg / KG_PER_LB
fun lbToKg(lb: Double) = lb * KG_PER_LB
fun cmToIn(cm: Double) = cm / CM_PER_IN
fun inToCm(inches: Double) = inches * CM_PER_IN
