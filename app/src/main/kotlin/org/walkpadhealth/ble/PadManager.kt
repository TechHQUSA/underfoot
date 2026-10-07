package org.walkpadhealth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import org.walkpadhealth.protocol.UrevoDriver
import java.util.UUID

private fun u16(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")
private val FFF0 = u16("fff0"); private val FFF1 = u16("fff1"); private val FFF2 = u16("fff2")
private val FTMS = u16("1826"); private val TREADMILL_DATA = u16("2acd")

@SuppressLint("MissingPermission") // callers verify BLUETOOTH_CONNECT before constructing
class PadManager(
    ctx: Context,
    private val onFff1: (ByteArray) -> Unit,
    private val onFtms: (ByteArray) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) : BleManager(ctx) {
    private var fff1: BluetoothGattCharacteristic? = null
    private var fff2: BluetoothGattCharacteristic? = null
    private var ftms: BluetoothGattCharacteristic? = null

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {}
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = onConnection(false)
            override fun onDeviceReady(device: BluetoothDevice) = onConnection(true)
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = onConnection(false)
        })
    }

    /**
     * `auto = true` is the low-duty background connect for a known pad: it waits with no timeout until the pad appears,
     * so a pad that is switched off costs almost no battery. `auto = false` is a direct connect for a freshly scanned pad.
     */
    fun connectTo(device: BluetoothDevice, auto: Boolean = false) {
        val req = connect(device).retry(3, 200).useAutoConnect(auto)
        (if (auto) req else req.timeout(15_000)).enqueue()
    }

    override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val s = gatt.getService(FFF0) ?: return false
            fff1 = s.getCharacteristic(FFF1); fff2 = s.getCharacteristic(FFF2)
            ftms = gatt.getService(FTMS)?.getCharacteristic(TREADMILL_DATA)
            return fff1 != null && fff2 != null
        }

        override fun initialize() {
            // Nordic's request queue runs these in order, so the handshake below is sent only after fff1 is subscribed.
            setNotificationCallback(fff1).with { _, d -> d.value?.let(onFff1) }
            enableNotifications(fff1).enqueue()
            ftms?.let { c ->
                setNotificationCallback(c).with { _, d -> d.value?.let(onFtms) }
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) enableNotifications(c).enqueue()
                else if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) enableIndications(c).enqueue()
            }
            UrevoDriver.handshakeFrames.forEach {
                writeCharacteristic(fff2, it, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
            }
        }

        override fun onServicesInvalidated() { fff1 = null; fff2 = null; ftms = null }
    }
}
