package org.underfoot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.underfoot.AppPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // sync starts the service only when the user chose to keep the pad connected: connecting wakes the pad
        try { WalkService.sync(ctx, AppPrefs(ctx)) }
        catch (e: IllegalStateException) { /* OS refused a background start; the user opens the app once */ }
    }
}
