package org.walkpadhealth.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.BuildConfig
import org.walkpadhealth.R
import org.walkpadhealth.data.ProfileEntity
import org.walkpadhealth.health.HEALTH_PERMISSIONS
import org.walkpadhealth.health.SyncScheduler
import org.walkpadhealth.service.WalkService

@Composable
fun SettingsScreen(profile: ProfileEntity?, prefs: AppPrefs, onSave: (Double, Double) -> Unit, onOpenRawLog: () -> Unit) {
    val ctx = LocalContext.current
    var w by remember(profile) { mutableStateOf(profile?.weightKg?.toString() ?: "") }
    var h by remember(profile) { mutableStateOf(profile?.heightCm?.toString() ?: "") }
    var auto by remember { mutableStateOf(prefs.autoRecord) }
    var crash by remember { mutableStateOf(prefs.crashOffer) }
    var taps by remember { mutableIntStateOf(0) }
    val hc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { SyncScheduler.enqueue(ctx) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_profile_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(w, { w = it }, label = { Text(stringResource(R.string.label_weight)) }, keyboardOptions = num, singleLine = true)
        OutlinedTextField(h, { h = it }, label = { Text(stringResource(R.string.label_height)) }, keyboardOptions = num, singleLine = true)
        Button(onClick = { onSave(w.toDoubleOrNull() ?: 0.0, h.toDoubleOrNull() ?: 0.0) }) { Text(stringResource(R.string.save_profile)) }
        HorizontalDivider()
        Button(onClick = { hc.launch(HEALTH_PERMISSIONS) }) { Text(stringResource(R.string.allow_hc)) }
        HorizontalDivider()
        Row { Text(stringResource(R.string.record_auto), Modifier.weight(1f)); Switch(auto, { auto = it; prefs.autoRecord = it; WalkService.sync(ctx, prefs) }) }
        OutlinedButton(onClick = { prefs.padAddress = null; WalkService.sync(ctx, prefs) }) { Text(stringResource(R.string.forget_pad)) }
        Row { Text(stringResource(R.string.offer_crash), Modifier.weight(1f)); Switch(crash, { crash = it; prefs.crashOffer = it }) }
        Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME), Modifier.clickable { if (++taps >= 7) { taps = 0; onOpenRawLog() } })
    }
}
