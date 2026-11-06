package com.wristradar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** GATT central side of a link. */
@SuppressLint("MissingPermission")
class ClientLink(private val context: Context, private val device: BluetoothDevice, private val longRange: Boolean = false) :
    Link(isClient = true, address = device.address) {

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var rssiWait: CompletableDeferred<Int?>? = null

    override val maxFragment: Int get() = mtu - 3
    override val deviceName: String? get() = runCatching { device.name }.getOrNull()

    suspend fun connect(timeoutMs: Long = 15_000): Boolean {
        // Max Range: allow the connection on the Coded PHY too (it is created on whichever PHY the advert was heard).
        val phy = BluetoothDevice.PHY_LE_1M_MASK or (if (longRange) BluetoothDevice.PHY_LE_CODED_MASK else 0)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, phy)
        if (gatt == null) { markClosed(); return false }
        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
        if (!ok) close()
        return ok
    }

    override fun writeFragment(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = rx ?: return false
        return g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BluetoothStatusCodes.SUCCESS
    }

    /** RSSI of this connection; serialized with writes because GATT allows one op at a time. */
    suspend fun readRssi(): Int? = opMutex.withLock {
        val g = gatt ?: return@withLock null
        val d = CompletableDeferred<Int?>()
        rssiWait = d
        if (!g.readRemoteRssi()) return@withLock null
        withTimeoutOrNull(2_000) { d.await() }
    }

    private var onCoded = false

    /**
     * Weak signal (far away) -> Coded PHY S2 (about double the range, half the speed);
     * strong signal -> 1M/2M PHY for fast pictures and clear calls. Hysteresis avoids flapping.
     */
    fun adaptPhy(rssi: Int) {
        if (!longRange) return
        val g = gatt ?: return
        if (!onCoded && rssi < -82) {
            onCoded = true
            runCatching { g.setPreferredPhy(BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S2) }
        } else if (onCoded && rssi > -70) {
            onCoded = false
            runCatching { g.setPreferredPhy(BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }
        }
    }

    fun setPriority(priority: Int) { runCatching { gatt?.requestConnectionPriority(priority) } }

    fun setHighPriority(high: Boolean) {
        gatt?.requestConnectionPriority(
            if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        )
    }

    override fun close() {
        val g = gatt
        gatt = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        ready.complete(false)
        markClosed()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(BleIds.REQUESTED_MTU)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(BleIds.SERVICE)
            val rxC = svc?.getCharacteristic(BleIds.RX)
            val txC = svc?.getCharacteristic(BleIds.TX)
            val cccd = txC?.getDescriptor(BleIds.CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || rxC == null || txC == null || cccd == null) {
                close(); return
            }
            rx = rxC
            g.setCharacteristicNotification(txC, true)
            val r = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (r != BluetoothStatusCodes.SUCCESS) close()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(true) else close()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            fragmentDone(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == BleIds.TX) onFragment(value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            rssiWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else null)
        }
    }
}
