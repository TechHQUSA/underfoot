package org.walkpadhealth

import android.content.Context

class AppPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var autoRecord: Boolean
        get() = p.getBoolean("auto", true); set(v) = p.edit().putBoolean("auto", v).apply()
    var rawLog: Boolean
        get() = p.getBoolean("rawlog", false); set(v) = p.edit().putBoolean("rawlog", v).apply()
    var crashOffer: Boolean
        get() = p.getBoolean("crash", true); set(v) = p.edit().putBoolean("crash", v).apply()
    /** Show Pause and Stop buttons. Off means the app stays read-only. */
    var controlsEnabled: Boolean
        get() = p.getBoolean("controls", true); set(v) = p.edit().putBoolean("controls", v).apply()
    /** Display units only; everything is stored in metric. */
    var imperial: Boolean
        get() = p.getBoolean("imperial", true); set(v) = p.edit().putBoolean("imperial", v).apply()
    /** MAC of the paired pad; connect directly when known, scan only when null. */
    var padAddress: String?
        get() = p.getString("pad", null); set(v) = p.edit().putString("pad", v).apply()
}
