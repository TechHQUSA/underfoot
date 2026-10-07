package org.walkpadhealth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walkpadhealth.Live
import org.walkpadhealth.Problem
import org.walkpadhealth.R
import org.walkpadhealth.data.SessionEntity
import org.walkpadhealth.protocol.BeltStatus

@Composable
fun TodayScreen(live: Live, today: DayTotals, recent: List<SessionEntity>, profileSet: Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        item { LiveCard(live) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(stringResource(R.string.stat_steps), today.steps.toString())
                    Stat(stringResource(R.string.stat_time), fmtDuration(today.activeSec))
                    Stat(stringResource(R.string.stat_km), fmtKm(today.distanceM))
                    Stat(stringResource(R.string.stat_kcal), "%.0f".format(today.kcal))
                }
            }
        }
        items(recent.take(5), key = { it.id }) { SessionRow(it) }
    }
}

@Composable private fun LiveCard(l: Live) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        val title = when {
            !l.connected -> R.string.looking_for_pad
            l.status == BeltStatus.RUNNING -> R.string.walking
            l.status == BeltStatus.IDLE -> R.string.connected_idle
            else -> R.string.connected
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(stringResource(R.string.stat_kmh), "%.1f".format(l.speedKmh))
            Stat(stringResource(R.string.stat_time), fmtDuration(l.activeSec))
            Stat(stringResource(R.string.stat_km), fmtKm(l.distanceM))
            Stat(stringResource(R.string.stat_kcal), "%.0f".format(l.kcal))
            Stat(stringResource(R.string.stat_steps), l.steps.toString())
        }
    }
}

@Composable fun Stat(label: String, value: String) = Column {
    Text(value, style = MaterialTheme.typography.titleLarge)
    Text(label, style = MaterialTheme.typography.labelSmall)
}

@Composable fun SessionRow(s: SessionEntity) = ListItem(
    headlineContent = { Text(stringResource(R.string.session_summary, fmtDuration(s.activeSec), fmtKm(s.distanceM), s.steps, s.kcal)) },
    supportingContent = {
        val est = listOf(s.distanceSource, s.stepsSource, s.kcalSource).count { it == "ESTIMATED" }
        val notes = buildList {
            if (est > 0) add(stringResource(R.string.session_estimated, est))
            if (!s.synced) add(stringResource(R.string.session_not_synced))
        }
        Text((listOf(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.startMs))) + notes).joinToString(" - "))
    },
)
