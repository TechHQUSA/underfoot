package org.underfoot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import kotlin.math.hypot
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.underfoot.protocol.SpeedTarget
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.OutlinedButton
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
import org.underfoot.CommandResult
import org.underfoot.Live
import org.underfoot.Problem
import org.underfoot.R
import org.underfoot.data.SessionEntity
import org.underfoot.protocol.BeltStatus
import org.underfoot.protocol.FtmsControl
import org.underfoot.protocol.PadCommand
import kotlin.math.cos
import kotlin.math.sin

/** The pad's top speed seen so far is 4.0 mph (6.44 km/h); the dial is full at that and just stays full above it. */
private const val DIAL_MAX_KMH = 6.44

@Composable
fun TodayScreen(
    live: Live, today: DayTotals, profileSet: Boolean, imperial: Boolean,
    controlsEnabled: Boolean, onCommand: (PadCommand) -> Unit, onSetSpeed: (Double) -> Unit, onReconnect: () -> Unit,
) {
    val allowed = FtmsControl.allowed(live.status, live.connected)
    var dragging by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = !dragging, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Header(live) }
        if (live.restingSec > 0) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.resting_text, ((live.restingSec + 59) / 60).toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onReconnect, shape = RoundedCornerShape(24.dp)) { Text(stringResource(R.string.reconnect_now)) }
            }
        }
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
                SpeedDial(live, imperial, adjustable = controlsEnabled && FtmsControl.canSetSpeed(live.status, live.connected), onDragging = { dragging = it }, onSetSpeed = onSetSpeed)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                StatCell(Icons.Filled.AccessTime, fmtDuration(live.activeSec), stringResource(R.string.stat_time).uppercase(), Modifier.weight(1f))
                StatDivider()
                StatCell(Icons.Filled.Straighten, fmtDistance(live.distanceM, imperial), stringResource(if (imperial) R.string.unit_mi else R.string.unit_km), Modifier.weight(1f))
                StatDivider()
                StatCell(Icons.Filled.LocalFireDepartment, "%.0f".format(live.kcal), stringResource(R.string.stat_kcal), Modifier.weight(1f))
                StatDivider()
                StatCell(Icons.AutoMirrored.Filled.DirectionsWalk, "%,d".format(live.steps), stringResource(R.string.stat_steps).uppercase(), Modifier.weight(1f))
            }
        }
        if (controlsEnabled) item { Controls(live, allowed, onCommand) }
        item { Text(stringResource(R.string.today_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Icons.Filled.AccessTime, fmtDuration(today.activeSec), "", stringResource(R.string.stat_time), Modifier.weight(1f))
                Tile(Icons.Filled.Straighten, fmtDistance(today.distanceM, imperial), stringResource(if (imperial) R.string.unit_mi else R.string.unit_km), stringResource(R.string.stat_distance), Modifier.weight(1f))
                Tile(Icons.Filled.LocalFireDepartment, "%.0f".format(today.kcal), stringResource(R.string.stat_kcal), stringResource(R.string.stat_energy), Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun Header(live: Live) {
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
                        live.restingSec > 0 -> R.string.status_resting
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
    }
}

/**
 * The speed dial. While the belt runs, drag the handle (a drag must start on it, so a stray touch cannot jump the speed) or tap
 * - and +. The speed is sent once, when the finger lifts (or 450 ms after the last tap), never mid-drag. Otherwise it only shows
 * the pad's speed.
 */
@Composable private fun SpeedDial(live: Live, imperial: Boolean, adjustable: Boolean, onDragging: (Boolean) -> Unit, onSetSpeed: (Double) -> Unit) {
    var drag by remember { mutableStateOf<Double?>(null) }     // km/h under the finger
    var tapped by remember { mutableStateOf<Double?>(null) }   // km/h chosen with - / +, waiting to be sent
    LaunchedEffect(tapped) {
        val t = tapped ?: return@LaunchedEffect
        delay(450); onSetSpeed(t); delay(3_000); if (tapped == t) tapped = null
    }
    val shown = drag ?: tapped ?: live.speedKmh
    val fraction = (shown / DIAL_MAX_KMH).toFloat().coerceIn(0f, 1f)
    val fractionNow by rememberUpdatedState(fraction)
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val progress = MaterialTheme.colorScheme.primary
    fun pick(x: Float, y: Float, w: Int, h: Int): Double? =
        DialMath.fractionAtOrNull((x - w / 2.0), (y - h / 2.0))?.let { SpeedTarget.snap(it * SpeedTarget.MAX_KMH, imperial) }

    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(adjustable) {
                    // Any touch on the dial freezes page scrolling until the finger lifts, so a slightly-off drag cannot move the page.
                    if (adjustable) awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false); onDragging(true)
                        waitForUpOrCancellation(); onDragging(false)
                    }
                }
                .pointerInput(adjustable) {
                    if (adjustable) {
                        var grabbed = false
                        detectDragGestures(
                            onDragStart = { o ->
                                val a = Math.toRadians((DialMath.START_DEG + DialMath.SWEEP_DEG * fractionNow).toDouble())
                                val r = size.width / 2 - 16.dp.toPx()
                                val hx = size.width / 2 + cos(a) * r; val hy = size.height / 2 + sin(a) * r
                                grabbed = hypot(o.x - hx, o.y - hy) <= 72.dp.toPx()
                                if (grabbed) { onDragging(true); drag = pick(o.x, o.y, size.width, size.height) }
                            },
                            onDrag = { c, _ -> if (grabbed) { c.consume(); pick(c.position.x, c.position.y, size.width, size.height)?.let { drag = it } } },
                            onDragEnd = { if (grabbed) { drag?.let(onSetSpeed); drag = null; onDragging(false) }; grabbed = false },
                            onDragCancel = { drag = null; onDragging(false); grabbed = false },
                        )
                    }
                },
        ) {
            val stroke = 16.dp.toPx()
            val arc = Size(size.width - stroke * 2, size.height - stroke * 2)
            val topLeft = Offset(stroke, stroke)
            val style = Stroke(width = stroke, cap = StrokeCap.Round)
            drawArc(track, startAngle = DialMath.START_DEG, sweepAngle = DialMath.SWEEP_DEG, useCenter = false, topLeft = topLeft, size = arc, style = style)
            if (fraction > 0f) drawArc(progress, startAngle = DialMath.START_DEG, sweepAngle = DialMath.SWEEP_DEG * fraction, useCenter = false, topLeft = topLeft, size = arc, style = style)
            val angle = Math.toRadians((DialMath.START_DEG + DialMath.SWEEP_DEG * fraction).toDouble())
            val r = arc.width / 2
            val c = Offset(size.width / 2 + (cos(angle) * r).toFloat(), size.height / 2 + (sin(angle) * r).toFloat())
            drawCircle(progress.copy(alpha = 0.25f), radius = stroke * 1.9f, center = c)   // glow
            drawCircle(progress, radius = stroke * 0.85f, center = c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmtSpeed(shown, imperial), fontSize = 80.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (adjustable) {
            IconButton({ tapped = SpeedTarget.step(tapped ?: live.speedKmh, -1, imperial) }, Modifier.align(Alignment.BottomStart).padding(start = 24.dp).size(52.dp)) {
                Icon(Icons.Filled.Remove, stringResource(R.string.speed_slower), Modifier.size(30.dp))
            }
            IconButton({ tapped = SpeedTarget.step(tapped ?: live.speedKmh, +1, imperial) }, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp).size(52.dp)) {
                Icon(Icons.Filled.Add, stringResource(R.string.speed_faster), Modifier.size(30.dp))
            }
        }
    }
}

@Composable private fun StatDivider() {
    Box(Modifier.width(1.dp).height(56.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)))
}

@Composable private fun StatCell(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun Tile(icon: ImageVector, value: String, unit: String, label: String, modifier: Modifier) {
    Card(
        modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (unit.isNotEmpty()) Text(" $unit", Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Pause (Resume while paused) and Stop. Each does only what a tap says; the screen never sends anything by itself. */
@Composable private fun Controls(live: Live, allowed: Set<PadCommand>, onCommand: (PadCommand) -> Unit) {
    val main = when (live.status) {
        BeltStatus.IDLE, BeltStatus.STOPPED -> PadCommand.START
        BeltStatus.PAUSED, BeltStatus.PAUSING -> PadCommand.RESUME
        else -> PadCommand.PAUSE
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Button(
                onClick = { onCommand(main) }, enabled = main in allowed,
                modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(32.dp),
            ) {
                Icon(if (main == PadCommand.PAUSE) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(when (main) { PadCommand.START -> R.string.btn_start; PadCommand.RESUME -> R.string.btn_resume; else -> R.string.btn_pause }),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = { onCommand(PadCommand.STOP) }, enabled = PadCommand.STOP in allowed,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.btn_stop)) }
                Text(stringResource(R.string.btn_emergency_stop), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
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
