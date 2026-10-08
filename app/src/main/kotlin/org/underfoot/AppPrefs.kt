package org.underfoot

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
    /** Minutes of pause after which the app ends the walk on the pad so it can sleep; 0 = never (the default). Needs controls on. */
    var endPausedMin: Int
        get() = p.getInt("endPaused", 0); set(v) = p.edit().putInt("endPaused", v).apply()
    /** Display units only; everything is stored in metric. */
    var imperial: Boolean
        get() = p.getBoolean("imperial", true); set(v) = p.edit().putBoolean("imperial", v).apply()
    /** Random per install, so Health Connect record ids never collide with those from an earlier install of this app. */
    val installId: String
        get() = p.getString("install", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("install", it).apply() }
    /** "dark" (the default), "light" or "system". */
    var theme: String
        get() = p.getString("theme", "dark") ?: "dark"; set(v) = p.edit().putString("theme", v).apply()
    /** MAC of the paired pad; connect directly when known, scan only when null. */
    var padAddress: String?
        get() = p.getString("pad", null); set(v) = p.edit().putString("pad", v).apply()
}
