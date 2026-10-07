package org.underfoot

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.underfoot.crash.CrashReporter
import org.underfoot.health.SyncScheduler
import org.underfoot.service.WalkService
import org.underfoot.ui.HistoryScreen
import org.underfoot.ui.MainViewModel
import org.underfoot.ui.RawLogScreen
import org.underfoot.ui.SettingsScreen
import org.underfoot.ui.TodayScreen
import org.underfoot.ui.UnderfootTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPrefs(this)
        setContent {
            var theme by remember { mutableStateOf(prefs.theme) }
            UnderfootTheme(theme) { App(prefs) { theme = it; prefs.theme = it } }
        }
    }

    override fun onResume() { super.onResume(); SyncScheduler.enqueue(this) }

    @Composable private fun App(prefs: AppPrefs, onTheme: (String) -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            WalkService.sync(this, prefs)          // starts only if Bluetooth permission was granted; otherwise Today shows why
        }
        LaunchedEffect(Unit) {
            if (prefs.autoRecord) { if (WalkService.btGranted(this@MainActivity)) WalkService.sync(this@MainActivity, prefs) else launcher.launch(perms) }
        }

        var crash by remember { mutableStateOf(CrashReporter.pending(this)) }
        if (crash != null) AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.crash_title)) },
            text = { Text(stringResource(R.string.crash_text)) },
            confirmButton = {
                TextButton({
                    // markOffered renames the file first, so the share URI points at the file that stays on disk
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", CrashReporter.markOffered(this))
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, getString(R.string.crash_chooser)))
                    crash = null
                }) { Text(stringResource(R.string.crash_send)) }
            },
            dismissButton = { TextButton({ CrashReporter.discard(this); crash = null }) { Text(stringResource(R.string.crash_discard)) } },
        )

        val live by vm.live.collectAsStateWithLifecycle()
        val today by vm.today.collectAsStateWithLifecycle()
        val sessions by vm.sessions.collectAsStateWithLifecycle()
        val profile by vm.profile.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var rawLog by rememberSaveable { mutableStateOf(false) }

        Scaffold(bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0; rawLog = false }, { Icon(Icons.Filled.DirectionsWalk, null) }, label = { Text(stringResource(R.string.tab_today)) })
                NavigationBarItem(tab == 1, { tab = 1; rawLog = false }, { Icon(Icons.Filled.History, null) }, label = { Text(stringResource(R.string.tab_history)) })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
            }
        }) { pad ->
            Box(Modifier.padding(pad)) {
                when {
                    tab == 0 -> TodayScreen(
                        live, today, profile != null, prefs.imperial, prefs.controlsEnabled,
                        onCommand = { WalkService.command(this@MainActivity, it) }, onSetSpeed = { WalkService.setSpeed(this@MainActivity, it) },
                    )
                    tab == 1 -> HistoryScreen(sessions, prefs.imperial)
                    rawLog -> RawLogScreen(prefs) { rawLog = false }
                    else -> SettingsScreen(profile, prefs, { w, h -> vm.saveProfile(w, h) }, { rawLog = true }, onTheme)
                }
            }
        }
    }
}
