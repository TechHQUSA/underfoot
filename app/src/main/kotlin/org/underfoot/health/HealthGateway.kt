// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import org.underfoot.data.SessionEntity
import org.underfoot.protocol.HealthPlan
import org.underfoot.protocol.PauseList
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

    /** Maps [HealthPlan] one to one; the spans, ids and values are decided (and tested) there. */
    override suspend fun write(s: SessionEntity) {
        val c = client() ?: error("Health Connect unavailable")
        val pauses = when (val p = PauseList.decode(s.pauses, s.startMs, s.endMs)) {
            is PauseList.Valid -> p.pauses
            is PauseList.Invalid -> throw IllegalArgumentException(p.reason)   // never as unpaused: that would cover the pauses again
        }
        val plan = HealthPlan.of("underfoot-${org.underfoot.AppPrefs(ctx).installId}-${s.id}", s.startMs, s.endMs, pauses, s.steps, s.distanceM, s.kcal)
        val off = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(s.startMs))
        fun at(ms: Long) = Instant.ofEpochMilli(ms)
        // Recorded by a device; one stable id per record; the version is constant because sessions are immutable.
        fun meta(id: String) = Metadata.autoRecorded(Device(type = Device.TYPE_UNKNOWN, manufacturer = "UREVO", model = "Walking pad"), clientRecordId = id, clientRecordVersion = 0L)
        val ses = plan.session
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = at(ses.startMs), startZoneOffset = off, endTime = at(ses.endMs), endZoneOffset = off,
                metadata = meta(ses.id), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING, title = "Walking pad",
                segments = ses.pauses.map { (a, b) -> ExerciseSegment(at(a), at(b), ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE) },
            ),
        )
        plan.steps.forEach { records += StepsRecord(at(it.startMs), off, at(it.endMs), off, it.value.toLong(), meta(it.id)) }
        plan.distance.forEach { records += DistanceRecord(at(it.startMs), off, at(it.endMs), off, Length.meters(it.value), meta(it.id)) }
        plan.kcal.forEach { records += TotalCaloriesBurnedRecord(at(it.startMs), off, at(it.endMs), off, Energy.kilocalories(it.value), meta(it.id)) }
        c.insertRecords(records)   // an equal clientRecordId and version is ignored, so a retry after an ambiguous failure cannot duplicate
    }
}
