package com.wristradar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wristradar.data.RangeMode

/**
 * BLE advertising of the WristRadar service (connectable, no device name in the legacy advert).
 *
 * Two advertisements can run at once:
 *  - a legacy 1M advert (always): compatible with every watch, phone and the PC companion;
 *  - in [RangeMode.MAX], an extra Bluetooth 5 extended advert on the LE Coded PHY ("long range",
 *    roughly 2-4x the distance of 1M in open space) at maximum transmit power. It also carries the
 *    display name, so nearby watches can show who you are without connecting first.
 */
@SuppressLint("MissingPermission")
class BleAdvertiser(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: AdvertiseCallback? = null
    private var currentKey: String? = null
    private var codedCallback: AdvertisingSetCallback? = null
    private var codedKey: String? = null

    val supported: Boolean get() = adapter?.isMultipleAdvertisementSupported == true

    /** Bluetooth 5 long-range (LE Coded PHY) extended advertising available on this watch. */
    val longRangeSupported: Boolean
        get() = runCatching { adapter?.isLeCodedPhySupported == true && adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)

    fun update(payload: AdvertPayload, mode: Int, range: RangeMode, name: String?, onResult: (Boolean, String?) -> Unit) {
        updateLegacy(payload, mode, range, onResult)
        // Long-range advert: fast (every 100 ms) while actively searching, normal (250 ms) otherwise.
        val fast = mode == AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        if (range == RangeMode.MAX && longRangeSupported) updateCoded(payload, name, fast, onResult) else stopCoded()
    }

    private fun updateLegacy(payload: AdvertPayload, mode: Int, range: RangeMode, onResult: (Boolean, String?) -> Unit) {
        val key = payload.encode().contentToString() + mode + range
        if (key == currentKey && callback != null) return
        stopLegacy()
        val adv = adapter?.bluetoothLeAdvertiser ?: run { onResult(false, "Advertising not available"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(
                when (range) {
                    RangeMode.MAX -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                    RangeMode.BALANCED -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                    RangeMode.STEALTH -> AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
                }
            )
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(BleIds.SERVICE_PARCEL)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(BleIds.SERVICE_PARCEL, payload.encode())
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = onResult(true, null)
            override fun onStartFailure(errorCode: Int) {
                if (callback === this) { callback = null; currentKey = null }
                onResult(false, advertiseError(errorCode))
            }
        }
        callback = cb
        currentKey = key
        runCatching { adv.startAdvertising(settings, data, scanResponse, cb) }
            .onFailure { callback = null; currentKey = null; onResult(false, it.message) }
    }

    private fun updateCoded(payload: AdvertPayload, name: String?, fast: Boolean, onResult: (Boolean, String?) -> Unit) {
        val body = payload.encodeWithName(name)
        val key = body.contentToString() + fast
        if (key == codedKey && codedCallback != null) return
        stopCoded()
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(true)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setInterval(if (fast) AdvertisingSetParameters.INTERVAL_LOW else AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(BleIds.SERVICE_PARCEL)
            .addServiceData(BleIds.SERVICE_PARCEL, body)
            .build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status != ADVERTISE_SUCCESS) {
                    if (codedCallback === this) { codedCallback = null; codedKey = null }
                    onResult(false, "Long-range advert failed ($status)")
                }
            }
        }
        codedCallback = cb
        codedKey = key
        runCatching { adv.startAdvertisingSet(params, data, null, null, null, cb) }
            .onFailure { codedCallback = null; codedKey = null }
    }

    fun stop() { stopLegacy(); stopCoded() }

    private fun stopLegacy() {
        val cb = callback ?: return
        callback = null
        currentKey = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }

    private fun stopCoded() {
        val cb = codedCallback ?: return
        codedCallback = null
        codedKey = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
    }

    private fun advertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advert too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising unsupported"
        else -> "Advertising error $code"
    }
}

/**
 * Filtered scanning for the WristRadar service UUID. A hardware filter is required for scans to
 * continue while the screen is off. Android allows at most 5 scan starts per 30 s per app, so
 * starts are rate-limited here. Where supported, scanning also listens on the long-range (Coded)
 * PHY so Max Range adverts from far away are heard.
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context, private val onResult: (ScanResult) -> Unit, private val onError: (Int) -> Unit) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val starts = ArrayDeque<Long>()
    private var activeMode: Int? = null
    private var wantedMode: Int? = null
    private var callback: ScanCallback? = null

    val running: Boolean get() = activeMode != null

    private val extended: Boolean
        get() = runCatching { adapter?.isLeExtendedAdvertisingSupported == true }.getOrDefault(false)

    fun start(mode: Int) {
        wantedMode = mode
        if (activeMode == mode) return
        handler.removeCallbacksAndMessages(null)
        val now = System.currentTimeMillis()
        while (starts.isNotEmpty() && now - starts.first() > 30_000) starts.removeFirst()
        if (starts.size >= 4) {
            val wait = 30_000 - (now - starts.first()) + 200
            handler.postDelayed({ wantedMode?.let { start(it) } }, wait)
            return
        }
        stopInternal()
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onResult)
            override fun onScanFailed(errorCode: Int) {
                if (callback === this) { callback = null; activeMode = null }
                onError(errorCode)
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(BleIds.SERVICE_PARCEL).build())
        val builder = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
        if (extended) builder.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)   // also hears Coded PHY
        runCatching { scanner.startScan(filters, builder.build(), cb) }.onSuccess {
            callback = cb; activeMode = mode; starts.addLast(now)
        }
    }

    fun stop() {
        wantedMode = null
        handler.removeCallbacksAndMessages(null)
        stopInternal()
    }

    private fun stopInternal() {
        val cb = callback ?: return
        callback = null
        activeMode = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
