package org.underfoot.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import org.underfoot.data.SessionEntity
import java.time.Instant
import java.time.ZoneId

val HEALTH_PERMISSIONS: Set<String> = setOf(
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    HealthPermission.getWritePermission(StepsRecord::class),
    HealthPermission.getWritePermission(DistanceRecord::class),
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
)

enum class HcState { UNAVAILABLE, NEEDS_PERMISSION, CONNECTED }

/** What the Settings button should offer: install Health Connect, ask for permission, or show it is connected. */
suspend fun healthConnectState(ctx: Context): HcState {
    if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) return HcState.UNAVAILABLE
    return try {
        val granted = HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
        if (granted.containsAll(HEALTH_PERMISSIONS)) HcState.CONNECTED else HcState.NEEDS_PERMISSION
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        HcState.NEEDS_PERMISSION
    }
}

class HealthConnectGateway(private val ctx: Context) : HealthGateway {
    private fun client(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(ctx) else null

    override suspend fun hasPermissions(): Boolean {
        val c = client() ?: return false
        return c.permissionController.getGrantedPermissions().containsAll(HEALTH_PERMISSIONS)
    }

    override suspend fun write(s: SessionEntity) {
        val c = client() ?: error("Health Connect unavailable")
        val start = Instant.ofEpochMilli(s.startMs)
        val end = Instant.ofEpochMilli(maxOf(s.endMs, s.startMs + 1000))
        val off = ZoneId.systemDefault().rules.getOffset(start)
        // Recorded by a device; one stable id per record type; the version is constant because sessions are immutable.
        fun meta(kind: String) = Metadata.autoRecorded(
            Device(type = Device.TYPE_UNKNOWN, manufacturer = "UREVO", model = "Walking pad"),
            clientRecordId = "underfoot-${org.underfoot.AppPrefs(ctx).installId}-${s.id}-$kind", clientRecordVersion = 0L,
        )
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                metadata = meta("exercise"), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING, title = "Walking pad",
            ),
        )
        if (s.steps > 0) records += StepsRecord(start, off, end, off, s.steps.toLong(), meta("steps"))
        if (s.distanceM > 0.0) records += DistanceRecord(start, off, end, off, Length.meters(s.distanceM), meta("distance"))
        if (s.kcal > 0.0) records += TotalCaloriesBurnedRecord(start, off, end, off, Energy.kilocalories(s.kcal), meta("kcal"))
        c.insertRecords(records)   // an equal clientRecordId and version is ignored, so a retry after an ambiguous failure cannot duplicate
    }
}
