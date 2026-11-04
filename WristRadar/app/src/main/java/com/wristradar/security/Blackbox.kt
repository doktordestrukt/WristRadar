package com.wristradar.security

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.wristradar.UiVisibility
import com.wristradar.ble.Link
import com.wristradar.data.SettingsStore
import com.wristradar.data.db.SecurityEventDao
import com.wristradar.data.db.SecurityEventEntity
import com.wristradar.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Only events that are cryptographic evidence of unauthorised access qualify. Ordinary scanning,
 * nearby devices, normal traffic and routine disconnects never reach this class.
 */
enum class ThreatType(val title: String) {
    UNAUTHORISED_DEVICE("Unauthenticated device sent encrypted data to the private channel"),
    AUTH_FAILED("Device failed identity authentication"),
    IMPERSONATION("Device using your partner's radio ID failed the identity check"),
    PAIRING_TAMPER("Pairing data was altered in transit"),
    CODE_MISMATCH("Pairing verification codes did not match"),
    TAMPERED_MESSAGE("Modified encrypted message rejected (invalid authentication tag)"),
    REPLAYED_MESSAGE("Replayed encrypted message rejected"),
    LOCAL_TAMPER("Protected local data failed its integrity check"),
    PARTNER_REPORTED("Your partner's device detected an intrusion attempt"),
}

/** Shown to the two legitimate users (never the skull). */
data class SecurityWarning(
    val type: ThreatType,
    val detail: String,
    val deviceName: String,
    val time: Long,
    val fromPartner: Boolean = false,
    val repeats: Int = 0,
)

data class BlackboxAlert(
    val type: ThreatType?,
    val detail: String,
    val deviceName: String,
    val deviceId: String?,
    val time: Long,
    /** This watch's own access attempt was refused by another watch. */
    val deniedHere: Boolean = false,
    /** Preview from Settings; not a real event and never logged. */
    val preview: Boolean = false,
    val repeats: Int = 0,
)

/**
 * BLACKBOX detector: logs qualifying events locally, raises one alert at a time (no overlapping
 * alerts; repeats inside the cooldown are only counted and logged), and vibrates or notifies.
 * The UI layer draws the skull and plays the bundled sounds. Nothing is ever sent off the watch.
 */
class Blackbox(
    private val ctx: Context,
    private val dao: SecurityEventDao,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val ui: UiVisibility,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _alert = MutableStateFlow<BlackboxAlert?>(null)
    val alert: StateFlow<BlackboxAlert?> = _alert.asStateFlow()
    val log = dao.observe()
    private val _warning = MutableStateFlow<SecurityWarning?>(null)
    /** Themed warning with instructions for the legitimate users. */
    val warning: StateFlow<SecurityWarning?> = _warning.asStateFlow()
    /** Engine hook: forwards a local event to the partner over the encrypted channel. */
    @Volatile var onLocalEvent: ((String) -> Unit)? = null
    @Volatile private var lastAlertAt = 0L
    @Volatile private var localTamperReported = false

    fun report(type: ThreatType, detail: String, link: Link?) =
        report(type, detail, link?.deviceName, link?.address)

    @Synchronized
    fun report(type: ThreatType, detail: String, deviceName: String?, deviceId: String?) {
        if (type == ThreatType.LOCAL_TAMPER) {
            if (localTamperReported) return
            localTamperReported = true
        }
        val name = deviceName?.takeIf { it.isNotBlank() } ?: "UNKNOWN DEVICE"
        val now = System.currentTimeMillis()
        scope.launch {
            dao.insert(SecurityEventEntity(time = now, type = type.name, detail = detail, device = name, deviceId = deviceId))
            dao.trim()
        }
        Log.w(TAG, "BLACKBOX event ${type.name}")   // type only - never content or keys

        if (type != ThreatType.PARTNER_REPORTED) onLocalEvent?.invoke(type.title)
        val current = _warning.value
        if (current != null) { _warning.value = current.copy(repeats = current.repeats + 1); return }
        if (now - lastAlertAt < COOLDOWN_MS) return
        lastAlertAt = now
        _warning.value = SecurityWarning(type, detail, name, now, fromPartner = type == ThreatType.PARTNER_REPORTED)
        buzz()
        if (!ui.appVisible) notifier.showBlackbox(type.title, name)
    }

    /** Another watch refused this watch's handshake and told us so. Shown, not logged as a threat. */
    @Synchronized
    fun accessDeniedHere(deviceName: String?, deviceId: String?) {
        val now = System.currentTimeMillis()
        if (_alert.value != null || now - lastAlertAt < COOLDOWN_MS) return
        lastAlertAt = now
        _alert.value = BlackboxAlert(
            null, "This watch tried to open a protected WristRadar channel and was refused.",
            deviceName?.takeIf { it.isNotBlank() } ?: "UNKNOWN DEVICE", deviceId, now, deniedHere = true,
        )
        buzz()
        if (!ui.appVisible) notifier.showBlackbox("Access denied by a protected watch", deviceName ?: "UNKNOWN DEVICE")
    }

    fun dismiss() {
        _alert.value = null
        notifier.cancelBlackbox()
    }

    fun dismissWarning() {
        _warning.value = null
        notifier.cancelBlackbox()
    }

    /** The partner's device reported an event (arrives over the encrypted channel). */
    fun partnerNotice(what: String) = report(ThreatType.PARTNER_REPORTED, what.take(200), "Partner's device", null)

    fun clearLog() = scope.launch { dao.clear() }

    fun reportLocalTamper(what: String) = report(ThreatType.LOCAL_TAMPER, what, "THIS WATCH", null)

    private fun buzz() {
        if (!settings.current.blackboxVibration) return
        val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
        runCatching { vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60, 40, 60, 120, 300), -1)) }
    }

    companion object {
        private const val TAG = "WristRadar"
        const val COOLDOWN_MS = 20_000L
    }
}
