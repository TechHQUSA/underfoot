package org.walkpadhealth

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import org.walkpadhealth.protocol.BeltStatus

enum class Problem { NONE, BLUETOOTH_OFF, PERMISSION, SCAN_FAILED }

data class Live(
    val problem: Problem = Problem.NONE,
    val connected: Boolean = false,
    val status: BeltStatus = BeltStatus.IDLE,
    val speedKmh: Double = 0.0,
    val activeSec: Long = 0,
    val distanceM: Double = 0.0,
    val steps: Int = 0,
    val kcal: Double = 0.0,
)

object LiveState { val flow = MutableStateFlow(Live()) }

class WalkpadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        org.walkpadhealth.crash.CrashReporter.install(this)   // defined in Task 10; stub until then
    }
}
