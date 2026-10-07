package org.underfoot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.underfoot.R
import org.underfoot.data.SessionEntity
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

@Composable
fun HistoryScreen(all: List<SessionEntity>, imperial: Boolean) {
    val today = remember(all) { LocalDate.now(zone) }
    val byDay = remember(all) { all.groupBy { dayOf(it.startMs) }.toSortedMap(compareByDescending { it }) }
    val week = remember(all) { all.filter { !dayOf(it.startMs).isBefore(today.minusDays(6)) } }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.tab_history), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (all.isEmpty()) item { EmptyHistory() }
        else {
            item {
                Text(stringResource(R.string.history_week), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(Icons.Filled.AccessTime, fmtDuration(week.sumOf { it.activeSec }), "", stringResource(R.string.stat_time), Modifier.weight(1f))
                    Tile(Icons.Filled.Straighten, fmtDistance(week.sumOf { it.distanceM }, imperial), stringResource(if (imperial) R.string.unit_mi else R.string.unit_km), stringResource(R.string.stat_distance), Modifier.weight(1f))
                    Tile(Icons.Filled.LocalFireDepartment, "%.0f".format(week.sumOf { it.kcal }), stringResource(R.string.stat_kcal), stringResource(R.string.stat_energy), Modifier.weight(1f))
                }
            }
            byDay.forEach { (day, sessions) ->
                item(key = "day-$day") { DayHeader(day, today) }
                items(sessions, key = { it.id }) { SessionCard(it, imperial) }
            }
        }
    }
}

@Composable private fun EmptyHistory() {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.DirectionsWalk, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.history_empty_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.history_empty_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun DayHeader(day: LocalDate, today: LocalDate) {
    val label = when (day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> remember(day) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(day.atStartOfDay(zone).toInstant())) }
    }
    Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable fun SessionCard(s: SessionEntity, imperial: Boolean) {
    val est = listOf(s.distanceSource, s.stepsSource, s.kcalSource).count { it == "ESTIMATED" }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtDuration(s.activeSec), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.startMs)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Mini(Icons.Filled.Straighten, fmtDistance(s.distanceM, imperial) + " " + stringResource(if (imperial) R.string.unit_mi else R.string.unit_km))
                Mini(Icons.Filled.DirectionsWalk, "%,d".format(s.steps))
                Mini(Icons.Filled.LocalFireDepartment, "%.0f".format(s.kcal) + " " + stringResource(R.string.stat_kcal))
            }
            if (est > 0 || !s.synced) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (est > 0) Chip(stringResource(R.string.session_estimated, est))
                    if (!s.synced) Chip(stringResource(R.string.session_not_synced))
                }
            }
        }
    }
}

@Composable private fun Mini(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
