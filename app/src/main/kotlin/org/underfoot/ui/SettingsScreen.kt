package org.underfoot.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.BorderStroke
import android.content.Intent
import android.net.Uri
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.underfoot.health.HcState
import org.underfoot.health.healthConnectState
import org.underfoot.AppPrefs
import org.underfoot.BuildConfig
import org.underfoot.R
import org.underfoot.data.ProfileEntity
import org.underfoot.health.HEALTH_PERMISSIONS
import org.underfoot.health.SyncScheduler
import org.underfoot.service.WalkService

private const val HC_PACKAGE = "com.google.android.apps.healthdata"

private fun openHealthConnectPage(ctx: Context) {
    val tries = listOf("market://details?id=$HC_PACKAGE", "https://play.google.com/store/apps/details?id=$HC_PACKAGE")
    for (uri in tries) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (e: ActivityNotFoundException) { /* try the next one */ }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(profile: ProfileEntity?, prefs: AppPrefs, onSave: (Double, Double) -> Unit, onOpenRawLog: () -> Unit, onTheme: (String) -> Unit) {
    val ctx = LocalContext.current
    var imperial by remember { mutableStateOf(prefs.imperial) }
    // The profile is stored in kg/cm; the fields show the chosen units and use '.' as the decimal mark so parsing matches.
    var w by remember(profile, imperial) {
        mutableStateOf(profile?.let { "%.1f".format(Locale.US, if (imperial) kgToLb(it.weightKg) else it.weightKg) } ?: "")
    }
    var h by remember(profile, imperial) {
        mutableStateOf(profile?.let { "%.1f".format(Locale.US, if (imperial) cmToIn(it.heightCm) else it.heightCm) } ?: "")
    }
    var auto by remember { mutableStateOf(prefs.autoRecord) }
    var crash by remember { mutableStateOf(prefs.crashOffer) }
    var controls by remember { mutableStateOf(prefs.controlsEnabled) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var taps by remember { mutableIntStateOf(0) }
    var hcState by remember { mutableStateOf<HcState?>(null) }
    var hcRefresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { hcRefresh++; onPauseOrDispose {} }          // also re-check when returning from the permission screen
    LaunchedEffect(hcRefresh) { hcState = healthConnectState(ctx) }
    val hc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        SyncScheduler.enqueue(ctx); hcRefresh++
    }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section(stringResource(R.string.settings_section_profile), stringResource(R.string.settings_profile_hint)) {
            SwitchRow(stringResource(R.string.use_imperial), null, imperial) { imperial = it; prefs.imperial = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(w, { w = it }, Modifier.weight(1f), label = { Text(stringResource(if (imperial) R.string.label_weight_lb else R.string.label_weight_kg)) }, keyboardOptions = num, singleLine = true)
                OutlinedTextField(h, { h = it }, Modifier.weight(1f), label = { Text(stringResource(if (imperial) R.string.label_height_in else R.string.label_height_cm)) }, keyboardOptions = num, singleLine = true)
            }
            Button(onClick = {
                val wv = w.toDoubleOrNull() ?: 0.0
                val hv = h.toDoubleOrNull() ?: 0.0
                onSave(if (imperial) lbToKg(wv) else wv, if (imperial) inToCm(hv) else hv)
            }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp)) { Text(stringResource(R.string.save_profile)) }
        }

        Section(stringResource(R.string.settings_section_look), null) {
            val modes = listOf("dark" to R.string.theme_dark, "light" to R.string.theme_light, "system" to R.string.theme_system)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (key, label) ->
                    SegmentedButton(theme == key, { theme = key; onTheme(key) }, SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(stringResource(label)) }
                }
            }
        }

        Section(stringResource(R.string.settings_section_health), null) {
            // Without the Health Connect app (Android 9-13) the permission contract has nowhere to go and launch() would throw.
            val full = Modifier.fillMaxWidth().height(48.dp)
            when (hcState) {
                null -> FilledTonalButton(onClick = {}, full, enabled = false) { Text(stringResource(R.string.hc_checking)) }
                HcState.CONNECTED -> FilledTonalButton(onClick = {}, full, enabled = false) {
                    Icon(Icons.Filled.Check, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.hc_connected))
                }
                HcState.NEEDS_PERMISSION -> Button(onClick = { hc.launch(HEALTH_PERMISSIONS) }, full, shape = RoundedCornerShape(24.dp)) { Text(stringResource(R.string.allow_hc)) }
                HcState.UNAVAILABLE -> Button(onClick = { openHealthConnectPage(ctx) }, full, shape = RoundedCornerShape(24.dp)) { Text(stringResource(R.string.install_hc)) }
            }
        }

        Section(stringResource(R.string.settings_section_pad), null) {
            SwitchRow(stringResource(R.string.record_auto), stringResource(R.string.record_auto_hint), auto) { auto = it; prefs.autoRecord = it; WalkService.sync(ctx, prefs) }
            SwitchRow(stringResource(R.string.settings_controls), stringResource(R.string.settings_controls_hint), controls) { controls = it; prefs.controlsEnabled = it }
            OutlinedButton(onClick = { prefs.padAddress = null; WalkService.sync(ctx, prefs) }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp)) {
                Text(stringResource(R.string.forget_pad))
            }
        }

        Section(stringResource(R.string.settings_section_privacy), null) {
            SwitchRow(stringResource(R.string.offer_crash), stringResource(R.string.offer_crash_hint), crash) { crash = it; prefs.crashOffer = it }
        }

        Text(
            stringResource(R.string.app_name) + " - " + stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            Modifier.fillMaxWidth().clickable { if (++taps >= 7) { taps = 0; onOpenRawLog() } }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable private fun Section(title: String, hint: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        } }
    }
}

@Composable private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}
