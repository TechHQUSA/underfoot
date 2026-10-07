package org.walkpadhealth.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walkpadhealth.LiveState
import org.walkpadhealth.data.AppDb
import org.walkpadhealth.data.ProfileEntity
import org.walkpadhealth.data.toProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayTotals(val steps: Int = 0, val activeSec: Long = 0, val kcal: Double = 0.0, val distanceM: Double = 0.0)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    val live = LiveState.flow
    val sessions = db.sessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profile = db.profile().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val today = sessions.map { list ->
        val zone = ZoneId.systemDefault(); val d = LocalDate.now(zone)
        val t = list.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == d }
        DayTotals(t.sumOf { it.steps }, t.sumOf { it.activeSec }, t.sumOf { it.kcal }, t.sumOf { it.distanceM })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayTotals())

    fun saveProfile(weightKg: Double, heightCm: Double) = viewModelScope.launch {
        if (weightKg in 20.0..300.0 && heightCm in 80.0..250.0) {
            val e = ProfileEntity(weightKg = weightKg, heightCm = heightCm)
            db.profile().upsert(e); AppDb.cachedProfile = e.toProfile()
        }
    }
}
