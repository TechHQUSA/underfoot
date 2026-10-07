package org.underfoot.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.underfoot.AppPrefs
import org.underfoot.LiveState
import org.underfoot.R
import org.underfoot.ble.FrameLog
import java.io.File

@Composable
fun RawLogScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val log = remember { FrameLog(File(ctx.filesDir, "raw")) }
    var on by remember { mutableStateOf(prefs.rawLog) }
    val chooser = stringResource(R.string.share_frame_log)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.rawlog_title), style = MaterialTheme.typography.titleMedium)
        val live by LiveState.flow.collectAsStateWithLifecycle()
        Text(stringResource(R.string.rawlog_counters, live.fff1Frames, live.lastFff1Len, live.ftmsFrames, live.status.name))
        Row { Text(stringResource(R.string.rawlog_record), Modifier.weight(1f)); Switch(on, { on = it; prefs.rawLog = it }) }
        Button(onClick = {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", log.file())
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, chooser))
        }) { Text(stringResource(R.string.rawlog_export)) }
        OutlinedButton(onClick = { log.clear() }) { Text(stringResource(R.string.rawlog_clear)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}
