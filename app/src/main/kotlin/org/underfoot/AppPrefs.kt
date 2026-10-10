package org.underfoot

import android.content.Context

class AppPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    /** Stay connected to the pad in the background, from boot on. Connecting wakes the pad and keeps it on, so this is opt-in. */
    var keepConnected: Boolean
        get() = p.getBoolean("keep", false); set(v) = p.edit().putBoolean("keep", v).apply()
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
    /** Random per install, so Health Connect record ids never collide with those from an earlier install of this app. */
    val installId: String
        get() = p.getString("install", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("install", it).apply() }
    /** "dark" (the default), "light" or "system". */
    var theme: String
        get() = p.getString("theme", "dark") ?: "dark"; set(v) = p.edit().putString("theme", v).apply()
    /** Minutes paused or idle before the app lets go of the pad so it can power itself off; 0 = never. */
    var sleepMin: Int
        get() = p.getInt("sleepmin", 0); set(v) = p.edit().putInt("sleepmin", v).apply()
    /** MAC of the paired pad; connect directly when known, scan only when null. */
    var padAddress: String?
        get() = p.getString("pad", null); set(v) = p.edit().putString("pad", v).apply()
}
