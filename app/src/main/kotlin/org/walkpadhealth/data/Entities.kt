package org.walkpadhealth.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val activeSec: Long,
    val distanceM: Double,
    val distanceSource: String,
    val steps: Int,
    val stepsSource: String,
    val kcal: Double,
    val kcalSource: String,
    val synced: Boolean = false,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val weightKg: Double,
    val heightCm: Double,
)
