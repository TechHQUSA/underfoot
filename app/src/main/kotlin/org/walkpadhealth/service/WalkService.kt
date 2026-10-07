package org.walkpadhealth.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.Live
import org.walkpadhealth.LiveState
import org.walkpadhealth.Problem
import org.walkpadhealth.R
import org.walkpadhealth.ble.FrameLog
import org.walkpadhealth.ble.PadManager
import org.walkpadhealth.data.AppDb
import org.walkpadhealth.data.FlushResult
import org.walkpadhealth.data.SessionStore
import org.walkpadhealth.data.toProfile
import kotlinx.coroutines.flow.collect
import org.walkpadhealth.health.SyncScheduler
import org.walkpadhealth.CommandResult
import org.walkpadhealth.protocol.BeltStatus
import org.walkpadhealth.protocol.CommandGate
import org.walkpadhealth.protocol.FtmsControl
import org.walkpadhealth.protocol.PadCommand
import org.walkpadhealth.protocol.Estimators
import org.walkpadhealth.protocol.Profile
import org.walkpadhealth.protocol.SessionSummary
import org.walkpadhealth.protocol.SessionTracker
import org.walkpadhealth.protocol.TelemetryMerger
import org.walkpadhealth.protocol.UrevoDriver
import java.io.File
import java.util.UUID

@SuppressLint("MissingPermission") // btGranted() is checked in onStartCommand before any BLE call
class WalkService : Service() {
    companion object {
        fun btGranted(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 31)
                listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                    .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
            else ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        const val ACTION_COMMAND = "org.walkpadhealth.COMMAND"
        const val EXTRA_COMMAND = "command"

        /** Called from the UI on a button tap. The service is not exported, so only this app can send it. */
        fun command(ctx: Context, cmd: PadCommand) {
            ctx.startService(Intent(ctx, WalkService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, cmd.name))
        }

        /** Single entry point for the UI, the boot receiver and the auto-record toggle. */
        fun sync(ctx: Context, prefs: AppPrefs) {
            val i = Intent(ctx, WalkService::class.java)
            if (prefs.autoRecord && btGranted(ctx)) ContextCompat.startForegroundService(ctx, i)
            else {
                ctx.stopService(i)
                LiveState.flow.value = Live(problem = if (prefs.autoRecord) Problem.PERMISSION else Problem.NONE)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = SessionTracker()
    private val merger = TelemetryMerger()
    private val gate = CommandGate()
    private var pendingCmd: PadCommand? = null
    private var pendingBefore = BeltStatus.IDLE        // belt status when the pending command was sent
    private var cmdSeq = 0
    private lateinit var prefs: AppPrefs
    private lateinit var log: FrameLog
    private var manager: PadManager? = null
    private var scanning = false
    private var connecting = false
    private var pendingAddress: String? = null     // saved as the paired pad only after the GATT services validate
    private var scanRetries = 0
    private var destroyed = false                  // stale posts from the old BLE manager must not revive a stopped service
    private var tickCount = 0
    private var last = Live()
    private val store by lazy { AppDb.get(this).let { SessionStore(it.sessions(), it.profile()) } }
    @Volatile private var profile = Profile.DEFAULT    // live display only; saved walks read the stored profile at save time

    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            tracker.tick(SystemClock.elapsedRealtime())?.let(::persist)
            if (++tickCount % 30 == 0 && store.hasPending) flush()
            publish(); main.postDelayed(this, 1000)
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { main.post { handleScanResult(result) } }
        override fun onScanFailed(errorCode: Int) { main.post { scanning = false; handleScanFailed() } }
    }

    private fun handleScanResult(result: ScanResult) {
        if (destroyed || connecting) return
        val name = result.scanRecord?.deviceName ?: result.device.name
        if (name?.startsWith("URTM") == true) { stopScan(); connect(result.device) }
    }

    /** Bounded retry: five attempts 10 s apart, then a visible SCAN_FAILED state until auto-record is toggled. */
    private fun handleScanFailed() {
        if (destroyed) return
        if (++scanRetries > 5) { last = last.copy(problem = Problem.SCAN_FAILED); publish(); return }
        main.postDelayed({ findPad() }, 10_000)
    }

    private fun connect(device: BluetoothDevice, auto: Boolean = false) {
        if (connecting) return
        connecting = true; pendingAddress = device.address
        try { manager?.connectTo(device, auto) } catch (e: RuntimeException) { connecting = false; recoverFromDeadGatt(e) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!btGranted(this)) { LiveState.flow.value = Live(problem = Problem.PERMISSION); stopSelf(); return START_NOT_STICKY }
        try { startInForeground() } catch (e: SecurityException) { LiveState.flow.value = Live(problem = Problem.PERMISSION); stopSelf(); return START_NOT_STICKY }
        prefs = AppPrefs(this)
        log = FrameLog(File(filesDir, "raw"))
        if (manager == null) {
            scope.launch { AppDb.get(this@WalkService).profile().observe().collect { profile = it?.toProfile() ?: Profile.DEFAULT } }
            manager = newManager()
            main.post(ticker)
            findPad()
        }
        if (intent?.action == ACTION_COMMAND) {
            runCatching { PadCommand.valueOf(intent.getStringExtra(EXTRA_COMMAND) ?: "") }.getOrNull()?.let(::runCommand)
        }
        return START_STICKY
    }

    private fun newManager() = PadManager(this,
        { b -> main.post { handleFff1(b) } }, { b -> main.post { handleFtms(b) } }, { c -> main.post { handleConnection(c) } },
        { b -> main.post { handleControlReply(b) } })

    /**
     * The Bluetooth stack can drop the GATT object without telling us (Bluetooth restarted, link lost silently); the next write then
     * throws DeadObjectException. Treat it as a lost connection: drop the old manager, start a fresh one and look for the pad again.
     */
    private fun recoverFromDeadGatt(e: RuntimeException) {
        Log.w("WalkpadBle", "GATT is dead, rebuilding the connection", e)
        manager?.close()
        manager = newManager()
        handleConnection(false)
    }

    /**
     * Runs one tap on Pause, Resume or Stop. Only what the current belt status allows is sent, Pause/Resume are rate-limited, and
     * nothing is ever sent by the app on its own. The outcome shows as a message only when the pad did not respond or refused.
     */
    private fun runCommand(cmd: PadCommand) {
        if (!prefs.controlsEnabled || cmd !in FtmsControl.allowed(last.status, last.connected)) return
        if (!gate.accept(cmd, SystemClock.elapsedRealtime())) return
        val m = manager ?: return
        pendingCmd = cmd; pendingBefore = last.status
        val seq = ++cmdSeq
        try {
            m.send(cmd) { written -> main.post { if (!written && pendingCmd == cmd && seq == cmdSeq) finishCommand(CommandResult.FAILED) } }
        } catch (e: RuntimeException) {
            finishCommand(CommandResult.NOT_CONFIRMED)
            recoverFromDeadGatt(e)
            return
        }
        main.postDelayed({ if (pendingCmd == cmd && seq == cmdSeq) finishCommand(CommandResult.NOT_CONFIRMED) }, 2_500)
    }

    private fun finishCommand(result: CommandResult) {
        pendingCmd = null
        last = last.copy(commandResult = result)
        publish()
        if (result != CommandResult.OK) main.postDelayed({
            if (last.commandResult == result) { last = last.copy(commandResult = CommandResult.NONE); publish() }
        }, 4_000)
    }

    private fun handleControlReply(bytes: ByteArray) {
        if (destroyed) return
        val r = FtmsControl.parseReply(bytes) ?: return
        val cmd = pendingCmd ?: return
        if (r.opcode != FtmsControl.opcode(cmd)) return      // the reply to the request-control write (opcode 0) and anything stale
        finishCommand(if (r.ok) CommandResult.OK else CommandResult.FAILED)
    }

    private fun handleFff1(bytes: ByteArray) {
        if (destroyed) return
        last = last.copy(fff1Frames = last.fff1Frames + 1, lastFff1Len = bytes.size)
        if (prefs.rawLog) log.append("fff1", bytes)
        val t = merger.merge(UrevoDriver.decodeFff1(bytes) ?: return)
        tracker.onTelemetry(t, SystemClock.elapsedRealtime())?.let(::persist)
        last = last.copy(status = t.status, speedKmh = if (t.status == BeltStatus.RUNNING) (t.speedKmh ?: 0.0) else 0.0)
        pendingCmd?.let { if (FtmsControl.confirmedBy(it, pendingBefore, t.status)) finishCommand(CommandResult.OK) }   // some pads never reply on the control point
        publish()
    }

    private fun handleFtms(bytes: ByteArray) {
        if (destroyed) return
        last = last.copy(ftmsFrames = last.ftmsFrames + 1)
        if (prefs.rawLog) log.append("2acd", bytes)
        UrevoDriver.decodeFtms(bytes)?.let(merger::onFtms)
    }

    private fun handleConnection(connected: Boolean) {
        if (destroyed) return
        connecting = false
        if (connected) { scanRetries = 0; pendingAddress?.let { prefs.padAddress = it } }   // onDeviceReady: services validated
        last = last.copy(connected = connected)
        if (!connected) {
            merger.reset()
            tracker.onDisconnect(SystemClock.elapsedRealtime())
            last = last.copy(status = BeltStatus.IDLE, speedKmh = 0.0)
            main.postDelayed({ findPad() }, 5_000)
        }
        publish()
    }

    /** Connect straight to the stored address, otherwise scan. Retries every 5 s while Bluetooth is off. */
    private fun findPad() {
        if (destroyed) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            last = last.copy(problem = Problem.BLUETOOTH_OFF); publish(); main.postDelayed({ findPad() }, 5_000); return
        }
        last = last.copy(problem = Problem.NONE)
        val addr = prefs.padAddress
        if (addr != null && BluetoothAdapter.checkBluetoothAddress(addr)) connect(adapter.getRemoteDevice(addr), auto = true) else startScan(adapter)
    }

    private fun startScan(adapter: BluetoothAdapter) {
        if (scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString("00001826-0000-1000-8000-00805f9b34fb"))).build()
        adapter.bluetoothLeScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCb)
        scanning = true
    }

    private fun stopScan() {
        if (!scanning) return
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(scanCb)
        scanning = false
    }

    private fun persist(s: SessionSummary) { store.enqueue(s); flush() }

    /** A failed save (for example storage full) shows a notification and is retried every 30 s by the ticker. */
    private fun flush() {
        scope.launch {
            when (store.flush()) {
                FlushResult.SAVED -> SyncScheduler.enqueue(this@WalkService)
                FlushResult.FAILED -> notifyStorageFull()
                FlushResult.NOTHING -> {}
            }
        }
    }

    private fun notifyStorageFull() {
        val n = NotificationCompat.Builder(this, "walk").setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.storage_full)).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun publish() {
        val p = tracker.progress()
        val prof = profile
        LiveState.flow.value = last.copy(
            activeSec = p.activeSec, distanceM = p.distanceM,
            steps = Estimators.steps(p.distanceM, prof.heightCm), kcal = Estimators.kcal(p.distanceM, p.activeSec.toDouble(), prof.weightKg),
        )
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("walk", getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "walk")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text)).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        stopScan()
        tracker.finish(SystemClock.elapsedRealtime())?.let(::persist)
        manager?.close()                           // disconnects and releases the GATT client; late callbacks are ignored via `destroyed`
        manager = null
        super.onDestroy()
    }
}
