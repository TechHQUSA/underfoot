package org.walkpadhealth.ui

import android.content.ActivityNotFoundException
import android.content.Context
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.BuildConfig
import org.walkpadhealth.R
import org.walkpadhealth.data.ProfileEntity
import org.walkpadhealth.health.HEALTH_PERMISSIONS
import org.walkpadhealth.health.SyncScheduler
import org.walkpadhealth.service.WalkService

private const val HC_PACKAGE = "com.google.android.apps.healthdata"

private fun openHealthConnectPage(ctx: Context) {
    val tries = listOf("market://details?id=$HC_PACKAGE", "https://play.google.com/store/apps/details?id=$HC_PACKAGE")
    for (uri in tries) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (e: ActivityNotFoundException) { /* try the next one */ }
    }
}

@Composable
fun SettingsScreen(profile: ProfileEntity?, prefs: AppPrefs, onSave: (Double, Double) -> Unit, onOpenRawLog: () -> Unit) {
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
    var taps by remember { mutableIntStateOf(0) }
    val hc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { SyncScheduler.enqueue(ctx) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_profile_title), style = MaterialTheme.typography.titleMedium)
        Row { Text(stringResource(R.string.use_imperial), Modifier.weight(1f)); Switch(imperial, { imperial = it; prefs.imperial = it }) }
        OutlinedTextField(w, { w = it }, label = { Text(stringResource(if (imperial) R.string.label_weight_lb else R.string.label_weight_kg)) }, keyboardOptions = num, singleLine = true)
        OutlinedTextField(h, { h = it }, label = { Text(stringResource(if (imperial) R.string.label_height_in else R.string.label_height_cm)) }, keyboardOptions = num, singleLine = true)
        Button(onClick = {
            val wv = w.toDoubleOrNull() ?: 0.0
            val hv = h.toDoubleOrNull() ?: 0.0
            onSave(if (imperial) lbToKg(wv) else wv, if (imperial) inToCm(hv) else hv)
        }) { Text(stringResource(R.string.save_profile)) }
        HorizontalDivider()
        // Without the Health Connect app (Android 9-13) the permission contract has nowhere to go and launch() would throw.
        val hcReady = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
        Button(onClick = { if (hcReady) hc.launch(HEALTH_PERMISSIONS) else openHealthConnectPage(ctx) }) {
            Text(stringResource(if (hcReady) R.string.allow_hc else R.string.install_hc))
        }
        HorizontalDivider()
        Row { Text(stringResource(R.string.record_auto), Modifier.weight(1f)); Switch(auto, { auto = it; prefs.autoRecord = it; WalkService.sync(ctx, prefs) }) }
        OutlinedButton(onClick = { prefs.padAddress = null; WalkService.sync(ctx, prefs) }) { Text(stringResource(R.string.forget_pad)) }
        Row { Text(stringResource(R.string.offer_crash), Modifier.weight(1f)); Switch(crash, { crash = it; prefs.crashOffer = it }) }
        Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME), Modifier.clickable { if (++taps >= 7) { taps = 0; onOpenRawLog() } })
    }
}
