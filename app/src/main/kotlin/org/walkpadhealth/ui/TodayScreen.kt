package org.walkpadhealth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.walkpadhealth.CommandResult
import org.walkpadhealth.Live
import org.walkpadhealth.Problem
import org.walkpadhealth.R
import org.walkpadhealth.data.SessionEntity
import org.walkpadhealth.protocol.BeltStatus
import org.walkpadhealth.protocol.FtmsControl
import org.walkpadhealth.protocol.PadCommand
import kotlin.math.cos
import kotlin.math.sin

/** The pad's top speed seen so far is 4.0 mph (6.44 km/h); the dial is full at that and just stays full above it. */
private const val DIAL_MAX_KMH = 6.44

@Composable
fun TodayScreen(
    live: Live, today: DayTotals, profileSet: Boolean, imperial: Boolean,
    controlsEnabled: Boolean, onCommand: (PadCommand) -> Unit, onOpenSettings: () -> Unit,
) {
    val allowed = FtmsControl.allowed(live.status, live.connected)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Header(live, onOpenSettings) }
        if (live.problem != Problem.NONE) item {
            Text(
                stringResource(when (live.problem) {
                    Problem.BLUETOOTH_OFF -> R.string.problem_bt_off
                    Problem.SCAN_FAILED -> R.string.problem_scan
                    else -> R.string.problem_permission
                }),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!profileSet) item { Text(stringResource(R.string.set_profile_hint), color = MaterialTheme.colorScheme.error) }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpeedDial(
                    value = fmtSpeed(live.speedKmh, imperial),
                    unit = stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh),
                    fraction = (live.speedKmh / DIAL_MAX_KMH).toFloat().coerceIn(0f, 1f),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell(Icons.Filled.AccessTime, fmtDuration(live.activeSec), stringResource(R.string.stat_time))
                StatCell(Icons.Filled.Straighten, fmtDistance(live.distanceM, imperial), stringResource(if (imperial) R.string.unit_mi else R.string.unit_km))
                StatCell(Icons.Filled.LocalFireDepartment, "%.0f".format(live.kcal), stringResource(R.string.stat_kcal))
                StatCell(Icons.Filled.DirectionsWalk, "%,d".format(live.steps), stringResource(R.string.stat_steps))
            }
        }
        if (controlsEnabled) item { Controls(live, allowed, onCommand) }
        item { Text(stringResource(R.string.today_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Icons.Filled.AccessTime, fmtDuration(today.activeSec), stringResource(R.string.stat_time), Modifier.weight(1f))
                Tile(Icons.Filled.Straighten, fmtDistance(today.distanceM, imperial), stringResource(if (imperial) R.string.unit_mi else R.string.unit_km), Modifier.weight(1f))
                Tile(Icons.Filled.LocalFireDepartment, "%.0f".format(today.kcal), stringResource(R.string.stat_kcal), Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun Header(live: Live, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = live.connected
                Box(Modifier.size(10.dp).background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(when {
                        !live.connected -> R.string.looking_for_pad
                        live.status == BeltStatus.RUNNING -> R.string.walking
                        live.status == BeltStatus.PAUSED || live.status == BeltStatus.PAUSING -> R.string.status_paused
                        live.status == BeltStatus.STARTING -> R.string.status_starting
                        else -> R.string.connected
                    }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.tab_settings)) }
    }
}

@Composable private fun SpeedDial(value: String, unit: String, fraction: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = MaterialTheme.colorScheme.primary
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val arc = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            val style = Stroke(width = stroke, cap = StrokeCap.Round)
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arc, style = style)
            if (fraction > 0f) drawArc(progress, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false, topLeft = topLeft, size = arc, style = style)
            val angle = Math.toRadians((135f + 270f * fraction).toDouble())
            val r = arc.width / 2
            drawCircle(progress, radius = stroke * 0.8f, center = Offset(size.width / 2 + (cos(angle) * r).toFloat(), size.height / 2 + (sin(angle) * r).toFloat()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(unit, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun StatCell(icon: ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Tile(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Pause (Resume while paused) and Stop. Each does only what a tap says; the screen never sends anything by itself. */
@Composable private fun Controls(live: Live, allowed: Set<PadCommand>, onCommand: (PadCommand) -> Unit) {
    val showResume = live.status == BeltStatus.PAUSED || live.status == BeltStatus.PAUSING
    val main = if (showResume) PadCommand.RESUME else PadCommand.PAUSE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Button(
                onClick = { onCommand(main) }, enabled = main in allowed,
                modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(32.dp),
            ) {
                Icon(if (showResume) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (showResume) R.string.btn_resume else R.string.btn_pause), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = { onCommand(PadCommand.STOP) }, enabled = PadCommand.STOP in allowed,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.btn_stop)) }
                Text(stringResource(R.string.btn_stop), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (live.commandResult == CommandResult.NOT_CONFIRMED || live.commandResult == CommandResult.FAILED) {
            Text(
                stringResource(if (live.commandResult == CommandResult.FAILED) R.string.cmd_failed else R.string.cmd_not_confirmed),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable fun SessionRow(s: SessionEntity, imperial: Boolean) = ListItem(
    headlineContent = {
        Text(stringResource(R.string.session_summary, fmtDuration(s.activeSec), fmtDistance(s.distanceM, imperial),
            stringResource(if (imperial) R.string.unit_mi else R.string.unit_km), s.steps, s.kcal))
    },
    supportingContent = {
        val est = listOf(s.distanceSource, s.stepsSource, s.kcalSource).count { it == "ESTIMATED" }
        val notes = buildList {
            if (est > 0) add(stringResource(R.string.session_estimated, est))
            if (!s.synced) add(stringResource(R.string.session_not_synced))
        }
        Text((listOf(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.startMs))) + notes).joinToString(" - "))
    },
)
