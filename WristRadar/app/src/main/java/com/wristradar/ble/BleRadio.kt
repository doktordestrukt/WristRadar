package com.wristradar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

/** BLE advertising of the WristRadar service (connectable, no device name, no personal data). */
@SuppressLint("MissingPermission")
class BleAdvertiser(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: AdvertiseCallback? = null
    private var currentKey: String? = null

    val supported: Boolean get() = adapter?.isMultipleAdvertisementSupported == true

    fun update(payload: AdvertPayload, mode: Int, onResult: (Boolean, String?) -> Unit) {
        val key = payload.encode().contentToString() + mode
        if (key == currentKey && callback != null) return
        stop()
        val adv = adapter?.bluetoothLeAdvertiser ?: run { onResult(false, "Advertising not available"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
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

    fun stop() {
        val cb = callback ?: return
        callback = null
        currentKey = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
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
 * starts are rate-limited here.
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
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()
        runCatching { scanner.startScan(filters, settings, cb) }.onSuccess {
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
