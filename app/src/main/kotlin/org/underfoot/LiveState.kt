package org.underfoot

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import org.underfoot.protocol.BeltStatus

enum class Problem { NONE, BLUETOOTH_OFF, PERMISSION, SCAN_FAILED }

/** Outcome of the last Pause/Resume/Stop tap. OK is not shown; the other two are. */
enum class CommandResult { NONE, OK, NOT_CONFIRMED, FAILED }

data class Live(
    val problem: Problem = Problem.NONE,
    val connected: Boolean = false,
    val status: BeltStatus = BeltStatus.IDLE,
    val speedKmh: Double = 0.0,
    val activeSec: Long = 0,
    val distanceM: Double = 0.0,
    val steps: Int = 0,
    val kcal: Double = 0.0,
    /** Frames received since the service started; shown on the hidden raw-log screen to tell "silent pad" from "bad decode". */
    val fff1Frames: Int = 0,
    val ftmsFrames: Int = 0,
    val lastFff1Len: Int = 0,
    val commandResult: CommandResult = CommandResult.NONE,
    /** Seconds left of the "let the pad sleep" hold-off; 0 when not resting. */
    val restingSec: Long = 0,
)

object LiveState { val flow = MutableStateFlow(Live()) }

class UnderfootApp : Application() {
    override fun onCreate() {
        super.onCreate()
        org.underfoot.crash.CrashReporter.install(this)   // defined in Task 10; stub until then
    }
}
