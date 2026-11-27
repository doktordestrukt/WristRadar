package com.wristradar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Server-side link: we notify on TX; the remote writes to our RX. */
class ServerLink(private val host: GattServerHost, val device: BluetoothDevice) :
    Link(isClient = false, address = device.address) {
    @Volatile var mtu = 23
    override val maxFragment: Int get() = when (codedLevel) { 8 -> minOf(mtu - 3, 120); 2 -> minOf(mtu - 3, 244); else -> mtu - 3 }
    @get:SuppressLint("MissingPermission")
    override val deviceName: String? get() = runCatching { device.name }.getOrNull()
    override fun writeFragment(bytes: ByteArray) = host.notify(device, bytes)
    override fun close() { host.disconnect(device); closeLocal() }
    /**
     * Stop using this link WITHOUT cutting the radio connection: cancelConnection() drops the
     * whole connection to that watch, including our own client link to it that we want to keep.
     * The other watch closes its side when it gets SESSION_DROP.
     */
    fun closeSoft() { host.forget(device); closeLocal() }

    private var codedLevel = 0

    /** Server side of Max Range: the friend's reported signal drives the same PHY steps. */
    fun adaptPhy(rssi: Int) {
        val want = PhyPolicy.level(rssi, codedLevel)
        if (want == codedLevel) return
        codedLevel = want
        host.setPhy(device, want)
    }
    internal fun closeLocal() = markClosed()
}

/**
 * Hosts the WristRadar GATT service. A [ServerLink] is created lazily on the first CCCD/RX write
 * from a remote central - plain ACL connections (including our own outgoing client connections,
 * which Android also reports to the server callback) never create links.
 */
@SuppressLint("MissingPermission")
class GattServerHost(private val context: Context, private val onNewLink: (ServerLink) -> Unit) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val links = ConcurrentHashMap<String, ServerLink>()
    private val mtus = ConcurrentHashMap<String, Int>()

    val isRunning get() = server != null

    fun start(): Boolean {
        if (server != null) return true
        val s = manager?.openGattServer(context, callback) ?: return false
        val service = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            BleIds.RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val txC = BluetoothGattCharacteristic(
            BleIds.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ
        )
        txC.addDescriptor(
            BluetoothGattDescriptor(
                BleIds.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(txC)
        tx = txC
        server = s
        return s.addService(service)
    }

    fun stop() {
        links.values.forEach { it.closeLocal() }
        links.clear()
        runCatching { server?.close() }
        server = null
    }

    fun notify(device: BluetoothDevice, bytes: ByteArray): Boolean {
        val s = server ?: return false
        val c = tx ?: return false
        return s.notifyCharacteristicChanged(device, c, false, bytes) == BluetoothStatusCodes.SUCCESS
    }

    fun forget(device: BluetoothDevice) { links.remove(device.address) }

    fun setPhy(device: BluetoothDevice, level: Int) {
        val s = server ?: return
        runCatching {
            when (level) {
                0 -> s.setPreferredPhy(device, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                2 -> s.setPreferredPhy(device, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S2)
                else -> s.setPreferredPhy(device, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S8)
            }
        }
    }

    fun disconnect(device: BluetoothDevice) {
        links.remove(device.address)
        runCatching { server?.cancelConnection(device) }
    }

    private fun linkFor(device: BluetoothDevice): ServerLink =
        links[device.address] ?: ServerLink(this, device).also { l ->
            l.mtu = mtus[device.address] ?: 23
            links[device.address] = l
            onNewLink(l)
        }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtus.remove(device.address)
                links.remove(device.address)?.closeLocal()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            links[device.address]?.mtu = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val s = server ?: return
            if (descriptor.uuid == BleIds.CCCD && descriptor.characteristic.uuid == BleIds.TX) {
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    linkFor(device)
                } else {
                    links.remove(device.address)?.closeLocal()
                }
            } else if (responseNeeded) {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val v = if (links.containsKey(device.address)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val s = server ?: return
            if (characteristic.uuid != BleIds.RX || preparedWrite || value == null) {
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            val link = linkFor(device)
            if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            link.onFragment(value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            links[device.address]?.fragmentDone(status == BluetoothGatt.GATT_SUCCESS)
        }
    }
}
