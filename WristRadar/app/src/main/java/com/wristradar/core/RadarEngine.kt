package com.wristradar.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.ContextCompat
import com.wristradar.AppGraph
import com.wristradar.ble.AdvertPayload
import com.wristradar.ble.BleAdvertiser
import com.wristradar.ble.BleIds
import com.wristradar.ble.BleScanner
import com.wristradar.ble.ClientLink
import com.wristradar.ble.DistanceEstimator
import com.wristradar.ble.GattServerHost
import com.wristradar.ble.Link
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.HandshakeClient
import com.wristradar.crypto.HandshakeServer
import com.wristradar.crypto.RecordError
import com.wristradar.security.ThreatType
import com.wristradar.crypto.SecureChannel
import com.wristradar.crypto.SessionKeys
import com.wristradar.data.ProfileStore
import com.wristradar.data.RangeMode
import com.wristradar.protocol.F
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import com.wristradar.protocol.frame
import com.wristradar.service.WristRadarService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Orchestrates radio policy, links, discovery, pairing and the authenticated session.
 * All mutable state is confined to a single-threaded dispatcher; BLE callbacks hop onto it.
 *
 * Roles: both watches run a GATT server + advertise, and both scan. For the paired link the watch
 * with the lexicographically lower identity key connects first; the other waits ~12 s before
 * trying itself. If both links complete, both sides keep the one whose client is the lower key.
 */
@SuppressLint("MissingPermission")
@OptIn(ExperimentalCoroutinesApi::class)
class RadarEngine(val ctx: Context, private val g: AppGraph) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _conn = MutableStateFlow(ConnectionState(if (g.trust.partner.value == null) Phase.NOT_PAIRED else Phase.STANDBY))
    val connection: StateFlow<ConnectionState> = _conn.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbyUser>>(emptyList())
    val nearby: StateFlow<List<NearbyUser>> = _nearby.asStateFlow()

    val pairing = PairingController(g, this)
    val messenger = Messenger(g, this)
    val calls = com.wristradar.audio.CallManager(ctx, g, this)
    val updater = Updater(ctx, g, this)
    val walk = WalkTracker(ctx, g.settings)
    val extras = FunHub(ctx, g, this)

    private val _presence = MutableStateFlow(Presence())
    val presence: StateFlow<Presence> = _presence.asStateFlow()
    private var typingJob: Job? = null
    private var lastPhoneCheck = 0L
    private var pendingNotice: String? = null

    init {
        g.blackbox.onLocalEvent = { title -> scope.launch { shareSecurityNotice(title) } }
    }

    private suspend fun shareSecurityNotice(title: String) {
        val s = currentSession()
        if (s == null || !messenger.sendSecurityNotice(s, title)) pendingNotice = title
    }

    fun onPartnerTyping(typing: Boolean) {
        typingJob?.cancel()
        _presence.update { it.copy(partnerTyping = typing) }
        if (typing) typingJob = scope.launch { delay(15_000); _presence.update { it.copy(partnerTyping = false) } }
    }

    fun onPartnerPhone(connected: Boolean) = _presence.update { it.copy(partnerPhone = connected) }

    private fun checkPhone(force: Boolean = false) {
        val t = now()
        if (!force && t - lastPhoneCheck < 60_000) return
        lastPhoneCheck = t
        scope.launch {
            val p = PhoneLink.isPhoneConnected(ctx)
            if (p != _presence.value.myPhone) {
                _presence.update { it.copy(myPhone = p) }
                currentSession()?.let { messenger.sendStatus(it, null) }
            }
        }
    }

    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val server = GattServerHost(ctx) { link -> scope.launch { attach(link) } }
    private val advertiser = BleAdvertiser(ctx)
    private val scanner = BleScanner(ctx, { r -> scope.launch { onScan(r) } }, { c -> Log.w(TAG, "scan failed $c") })

    @Volatile var running = false; private set
    private var receiverRegistered = false
    private var tickJob: Job? = null

    // ---- discovery ----
    private var discoveryRefs = 0
    val discoveryActive: Boolean get() = discoveryRefs > 0
    var myDiscoveryId = 0; private set

    private class NearbyEntry(val id: Int, var device: BluetoothDevice) {
        var name: String? = null
        var avatar: Bitmap? = null
        val est = DistanceEstimator()
        var lastSeen = 0L
        var fetched = false
        var fetchFails = 0
    }
    private val nearbyMap = HashMap<Int, NearbyEntry>()
    private var fetchingProfile = false

    // ---- partner link ----
    private var session: SecureSession? = null
    private var connecting = false
    private var partnerDevice: BluetoothDevice? = null
    private var partnerLastSeen = 0L
    private var partnerFirstSeen = 0L
    private val partnerEst = DistanceEstimator()
    private var remoteBattery: Int? = null
    private var boostUntil = 0L
    private var failCount = 0
    private var nextAttemptAt = 0L
    private var refreshDeadline = 0L
    private var failedAt = 0L
    private var lastStatusSent = 0L
    /** When the current search for the partner began (0 = connected / not searching). */
    private var searchingSince = 0L
    private var linkPriority = -1
    @Volatile private var oldestUnackedAt: Long? = null
    private var tokenWindow = -1L
    private var partnerTokens: List<ByteArray> = emptyList()
    private var randomToken = Crypto.random(8)
    private var rejectedRecords = 0

    private val authenticatedLinks = HashSet<Int>()
    private var renewing = false
    /** Last time an app message moved over the session (used to renew keys only when idle). */
    @Volatile var lastActivity = 0L
    /** KEM strength of the current session (null = not connected). */
    @Volatile var sessionKem: com.wristradar.crypto.MlKem.Level? = null
        private set
    @Volatile private var strongKemFailed = false
    /** Suite of the current session (null = not connected). */
    @Volatile var sessionSuite: com.wristradar.crypto.Suite? = null
        private set
    @Volatile private var pqSentFor: SecureSession? = null

    /** My ML-DSA-87 identity + the friend's, or null while we don't have theirs yet. */
    private fun pqIdentity(): com.wristradar.crypto.PqIdentity? {
        val theirs = g.trust.partnerPq ?: return null
        return runCatching { com.wristradar.crypto.PqIdentity(g.identity.pq, theirs) }.getOrNull()
    }

    /** Sends my post-quantum identity once per session to a friend whose app understands it. */
    fun maybeSendPqIdentity() {
        val s = currentSession() ?: return
        if (pqSentFor === s || updater.knownPartnerCode < SUITE_V4_SINCE) return
        pqSentFor = s
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val kp = g.identity.pq
            val pop = kp.sign("WR-PQID".toByteArray() + g.identity.publicKey)
            s.send(com.wristradar.protocol.A.PQ_ID, com.wristradar.protocol.Out().bytes(kp.publicKey).bytes(pop).build())
        }
    }

    /** Friend's post-quantum identity arrived over our authenticated session. */
    fun onPartnerPqIdentity(p: ByteArray) {
        val partner = g.trust.partner.value ?: return
        val r = com.wristradar.protocol.In(p)
        val pub = r.bytes(com.wristradar.crypto.MlDsa.PUBLIC_KEY_SIZE + 16) ?: return
        val pop = r.bytes(com.wristradar.crypto.MlDsa.SIGNATURE_SIZE + 16) ?: return
        if (!com.wristradar.crypto.MlDsa.verify(pub, pop, "WR-PQID".toByteArray() + partner.identityKey)) {
            Log.w(TAG, "post-quantum identity: bad proof of possession"); return
        }
        if (!g.trust.savePartnerPq(pub)) Log.w(TAG, "post-quantum identity changed without re-pairing - ignored")
    }
    /** Friend version for which ML-KEM-1024 failed: don't retry until they install a different one (no reconnect loops). */
    @Volatile private var strongFailedAtCode = -1

    /** Friend's app version just arrived: a new version gets another chance at ML-KEM-1024. */
    fun onPartnerVersion(code: Int) {
        if (code >= KEM1024_SINCE && code != strongFailedAtCode) strongKemFailed = false
        maybeSendPqIdentity()
    }
    /** Last time the current session delivered a valid message (the friend sends status at least every 45 s). */
    @Volatile private var lastInbound = 0L

    private class Waiter(val types: Set<Byte>, val d: CompletableDeferred<ByteArray?>)
    private val waiters = HashMap<Int, Waiter>()
    private val serverHandshakes = HashMap<Int, HandshakeServer>()

    fun currentSession(): SecureSession? = session?.takeIf { !it.link.closed }

    // =================================================================== lifecycle

    fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start() = scope.launch {
        updater.start()
        walk.start()
        if (running) { applyRadio(); return@launch }
        if (!hasPermissions()) { setPhase(Phase.NO_PERMISSION, "Allow Nearby devices permission"); return@launch }
        val a = adapter ?: run { setPhase(Phase.UNSUPPORTED, "No Bluetooth adapter"); return@launch }
        running = true
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                ctx, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        tickJob?.cancel()
        tickJob = scope.launch { while (isActive) { delay(TICK_MS); tick() } }
        if (a.isEnabled) onBluetoothOn() else setPhase(Phase.BLUETOOTH_OFF, "Turn Bluetooth on to connect")
    }

    /** Stops radios when nothing needs them (not paired, not discovering). */
    fun stopIfIdle() = scope.launch {
        if (g.trust.partner.value == null && !discoveryActive && !pairing.state.value.active) shutdown()
    }

    fun shutdown() = scope.launch {
        if (!running) return@launch
        running = false
        tickJob?.cancel()
        if (receiverRegistered) { runCatching { ctx.unregisterReceiver(btReceiver) }; receiverRegistered = false }
        closeAll()
        scanner.stop(); advertiser.stop(); server.stop()
        setPhase(baselinePhase(), "")
    }

    private fun onBluetoothOn() {
        if (!server.start()) { setPhase(Phase.FAILED, "Could not start GATT server"); return }
        if (!advertiser.supported) {
            setPhase(Phase.UNSUPPORTED, "This watch cannot advertise over BLE"); return
        }
        if (g.trust.partner.value != null) { boostUntil = now() + BOOST_MS; setPhase(Phase.SEARCHING, "Looking for your partner…") }
        else setPhase(Phase.NOT_PAIRED, "Pair with a nearby watch to start")
        applyRadio()
    }

    private fun onBluetoothOff() {
        closeAll()
        scanner.stop(); advertiser.stop(); server.stop()
        setPhase(Phase.BLUETOOTH_OFF, "Turn Bluetooth on to connect")
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            scope.launch {
                when (state) {
                    BluetoothAdapter.STATE_ON -> if (running) onBluetoothOn()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                }
            }
        }
    }

    private fun closeAll() {
        session?.close(); session = null
        connecting = false
        waiters.values.forEach { it.d.complete(null) }; waiters.clear()
        serverHandshakes.clear()
    }

    // =================================================================== radio policy

    private fun applyRadio() {
        if (!running || adapter?.isEnabled != true) { scanner.stop(); advertiser.stop(); return }
        val partner = g.trust.partner.value
        val connected = currentSession() != null
        val t = now()
        // Battery: search hard for the first 2 minutes (or while boosted), then drop to low power.
        // A low-power scan still finds the friend - just a few seconds slower.
        if (connected || partner == null) searchingSince = 0L else if (searchingSince == 0L) searchingSince = t
        val searchingLong = searchingSince != 0L && t - searchingSince > SEARCH_HARD_MS
        val wantAdvert = discoveryActive || pairing.state.value.active || (partner != null && !connected)
        if (wantAdvert) {
            val window = AdvertPayload.currentWindow(t)
            val token = if (partner != null) {
                AdvertPayload.token(partner.advertKeyFor(g.identity.publicKey), window)
            } else {
                if (window != tokenWindow) randomToken = Crypto.random(8)
                randomToken
            }
            if (partner != null && window != tokenWindow) {
                val k = partner.advertKeyFor(partner.identityKey)
                partnerTokens = listOf(window - 1, window, window + 1).map { AdvertPayload.token(k, it) }
            }
            tokenWindow = window
            val payload = AdvertPayload(discoveryActive, if (discoveryActive) myDiscoveryId else 0, token)
            val range = g.settings.current.range
            val mode = when {
                discoveryActive || t < boostUntil -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                range == RangeMode.MAX && !searchingLong -> AdvertiseSettings.ADVERTISE_MODE_BALANCED   // found faster from far away
                else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            }
            advertiser.update(payload, mode, range, g.profile.myName.value) { ok, err -> if (!ok) Log.w(TAG, "advertise: $err") }
        } else advertiser.stop()

        val scanMode = when {
            discoveryActive -> ScanSettings.SCAN_MODE_LOW_LATENCY
            partner != null && !connected -> when {
                t < boostUntil -> ScanSettings.SCAN_MODE_LOW_LATENCY
                g.settings.current.range == RangeMode.MAX && !searchingLong -> ScanSettings.SCAN_MODE_BALANCED
                else -> ScanSettings.SCAN_MODE_LOW_POWER
            }
            else -> null
        }
        if (scanMode == null) scanner.stop() else scanner.start(scanMode)
    }

    private fun onScan(r: ScanResult) {
        val payload = AdvertPayload.parse(r.scanRecord?.getServiceData(BleIds.SERVICE_PARCEL)) ?: return
        val t = now()
        if (g.trust.partner.value != null && partnerTokens.any { it.contentEquals(payload.token) }) {
            onPartnerSeen(r.device, r.rssi)
        }
        if (payload.discoverable && discoveryActive && payload.discoveryId != myDiscoveryId && payload.discoveryId != 0) {
            val e = nearbyMap.getOrPut(payload.discoveryId) { NearbyEntry(payload.discoveryId, r.device) }
            e.device = r.device
            e.est.add(r.rssi)
            e.lastSeen = t
            if (payload.name != null && e.name == null) e.name = payload.name   // long-range advert: name without connecting
            publishNearby()
            maybeFetchProfiles()
        }
    }

    private fun onPartnerSeen(device: BluetoothDevice, rssi: Int) {
        val t = now()
        partnerEst.add(rssi)
        partnerDevice = device
        if (t - partnerLastSeen > 15_000) partnerFirstSeen = t
        partnerLastSeen = t
        if (currentSession() == null) {
            publishDistance()
            if (!connecting && _conn.value.phase in setOf(Phase.SEARCHING, Phase.REFRESHING, Phase.FAILED, Phase.STANDBY)) {
                setPhase(Phase.DEVICE_FOUND, "Partner in range")
            }
            maybeConnect()
        }
    }

    // =================================================================== partner connection

    private fun maybeConnect() {
        val partner = g.trust.partner.value ?: return
        if (!running || currentSession() != null || connecting || pairing.state.value.active) return
        val device = partnerDevice ?: return
        val t = now()
        if (t - partnerLastSeen > 10_000 || t < nextAttemptAt) return
        val iAmLower = Crypto.compare(g.identity.publicKey, partner.identityKey) < 0
        if (!iAmLower && t - partnerFirstSeen < 12_000 && t > boostUntil) return
        connecting = true
        scope.launch {
            setPhase(Phase.CONNECTING, "Opening link…")
            val link = openClientLink(device)
            if (link == null) { onConnectFailed("Could not connect"); return@launch }
            handshakeAsClient(link)
        }
    }

    private suspend fun handshakeAsClient(link: Link) {
        connecting = true
        val partner = g.trust.partner.value ?: run { link.close(); connecting = false; return }
        setPhase(Phase.AUTHENTICATING, "Verifying partner identity…")
        // ML-KEM-1024 once we know the friend's app understands it; otherwise (or if that just
        // failed) the ML-KEM-768 handshake every version speaks.
        val strong = updater.knownPartnerCode >= KEM1024_SINCE && !strongKemFailed
        // Top suite V4 (adds ML-DSA-87 + SHA-384) once we hold the friend's post-quantum identity.
        val pq = if (strong && updater.knownPartnerCode >= SUITE_V4_SINCE) pqIdentity() else null
        val hc = HandshakeClient(g.identity, partner.identityKey, partner.psk,
            if (strong) com.wristradar.crypto.MlKem.Level.ML_KEM_1024 else com.wristradar.crypto.MlKem.Level.ML_KEM_768, pq)
        val resp = request(link, frame(F.HS_HELLO, hc.hello()), setOf(F.HS_SERVER, F.HS_REJECT, F.BLACKBOX), 10_000)
        if (resp != null && resp[0] == F.BLACKBOX) {
            link.close()
            g.blackbox.accessDeniedHere(link.deviceName, link.address)
            onConnectFailed("Access denied by the other watch")
            return
        }
        if (resp == null || resp[0] == F.HS_REJECT) {
            if (strong) { strongKemFailed = true; strongFailedAtCode = updater.knownPartnerCode }   // retry with ML-KEM-768
            link.close()
            onConnectFailed(if (resp == null) "No response from partner" else "Partner refused the session (pairing removed?)")
            return
        }
        val res = hc.onServer(resp.copyOfRange(1, resp.size))
        if (res == null && hc.failedOnlyPq) {
            // Friend proved PSK + Ed25519 but the post-quantum key didn't match: fall back, no alarm.
            Log.w(TAG, "suite V4 signature mismatch - falling back")
            strongKemFailed = true; strongFailedAtCode = updater.knownPartnerCode
            link.close(); onConnectFailed("Retrying with compatible encryption"); return
        }
        if (res == null) {
            // It advertised our partner's secret rolling token yet cannot prove the partner's identity.
            g.blackbox.report(ThreatType.IMPERSONATION, "Handshake response failed signature/PSK verification", link)
            sendBlackbox(link)
            onConnectFailed("Authentication failed: device is not your trusted partner")
            return
        }
        if (!link.send(frame(F.HS_FINISH, res.first))) { onConnectFailed("Connection lost"); return }
        connecting = false
        establish(link, res.second)
    }

    private fun onConnectFailed(msg: String) {
        connecting = false
        failCount++
        // Retry quickly: at the edge of range a reconnect often works a few seconds later.
        nextAttemptAt = now() + minOf(20_000L, 3_000L * failCount)
        if (refreshDeadline != 0L || failCount >= 3) { failedAt = now(); setPhase(Phase.FAILED, msg) }
        else setPhase(Phase.SEARCHING, "$msg – retrying")
    }

    private fun establish(link: Link, keys: SessionKeys) {
        val partner = g.trust.partner.value ?: run { link.close(); return }
        val existing = currentSession()
        if (existing != null) {
            val iAmLower = Crypto.compare(g.identity.publicKey, partner.identityKey) < 0
            // Both watches connecting at the same moment: keep the deterministic preferred link.
            // But if our session is older than a few seconds, the friend only reconnected because
            // THEIR side of it died - our old session is a zombie, so the new link always wins.
            val simultaneous = now() - existing.startedAt < SIMULTANEOUS_MS
            if (simultaneous && link.isClient != iAmLower) { link.close(); return }
            Log.i(TAG, "replacing session (age ${now() - existing.startedAt} ms) with a new link")
            session = null
            existing.close()
        }
        val s = SecureSession(link, SecureChannel(keys, link.isClient))
        session = s
        sessionKem = keys.kem
        sessionSuite = keys.suite
        lastInbound = now()
        authenticatedLinks += link.id
        renewing = false
        connecting = false
        failCount = 0; nextAttemptAt = 0; refreshDeadline = 0
        g.trust.markConnected()
        g.trust.markPartnerSeen()
        checkPhone(force = true)
        pendingNotice?.let { n -> pendingNotice = null; scope.launch { shareSecurityNotice(n) } }
        setPhase(Phase.CONNECTED, "End-to-end encrypted")
        applyRadio()
        WristRadarService.start(ctx)
        updater.prepareSession()
        scope.launch {
            for (m in s.inbox) {
                if (m.isEmpty()) continue
                if (m[0] != com.wristradar.protocol.A.STATUS) lastActivity = System.currentTimeMillis()
                try { messenger.handle(s, m[0], m.copyOfRange(1, m.size)) }
                catch (e: Exception) { Log.w(TAG, "bad app message type=${m[0]}: ${e.javaClass.simpleName}") }
            }
        }
        scope.launch { updater.onSessionUp(s); messenger.onSessionUp(s); extras.onSessionUp(s) }
    }

    // =================================================================== links & frames

    suspend fun openClientLink(device: BluetoothDevice, attempts: Int = 3, timeoutMs: Long = 15_000): ClientLink? {
        // Android BLE often fails a first connect with status 133; retry like the PC companion does.
        val longRange = g.settings.current.range == RangeMode.MAX && advertiser.longRangeSupported
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(800)
            val l = ClientLink(ctx, device, longRange)
            attach(l)
            if (l.connect(timeoutMs)) return l
        }
        return null
    }

    private fun attach(link: Link) {
        link.onFrame = { l, f -> scope.launch { onFrame(l, f) } }
        link.onClosed = { l -> scope.launch { onLinkClosed(l) } }
    }

    private suspend fun request(link: Link, f: ByteArray, types: Set<Byte>, timeoutMs: Long): ByteArray? {
        val d = CompletableDeferred<ByteArray?>()
        waiters[link.id] = Waiter(types, d)
        if (!link.send(f)) { waiters.remove(link.id); return null }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        waiters.remove(link.id)
        return r
    }

    /** Never suspends: frame order per link is preserved by the FIFO single-thread dispatcher. */
    private fun onFrame(l: Link, f: ByteArray) {
        if (f.isEmpty() || l.closed) return
        waiters[l.id]?.let { w ->
            if (f[0] in w.types) { waiters.remove(l.id); w.d.complete(f); return }
        }
        when (f[0]) {
            F.PROFILE_REQ -> if (discoveryActive && !l.isClient) scope.launch {
                val thumb = g.profile.myAvatarThumb()
                l.send(frame(F.PROFILE_RESP, Out().i(myDiscoveryId).str(g.profile.myName.value).bytes(thumb).build()))
            } else l.close()
            F.PAIR_COMMIT, F.PAIR_RESPONSE, F.PAIR_REVEAL, F.PAIR_CONFIRM, F.PAIR_ABORT -> pairing.onFrame(l, f)
            F.HS_HELLO -> onHello(l, f)
            F.HS_FINISH -> onFinish(l, f)
            F.SECURE -> {
                val s = session
                if (s == null || s.link !== l) {
                    // Encrypted traffic on a link that never authenticated: a client skipping the
                    // handshake. Links that were authenticated earlier (replaced/renewed) are ignored.
                    if (l.id !in authenticatedLinks && !l.isClient) {
                        g.blackbox.report(ThreatType.UNAUTHORISED_DEVICE, "Encrypted record received without a completed handshake", l)
                        sendBlackbox(l)
                    }
                    return
                }
                val plain = s.open(f.copyOfRange(1, f.size))
                if (plain != null && l === s.link) lastInbound = now()
                if (plain == null) {
                    rejectedRecords++
                    Log.w(TAG, "rejected record #$rejectedRecords")
                    val what = if (s.lastError == RecordError.REPLAY) "replay an old message" else "alter a message"
                    scope.launch { messenger.insertAlert("⚠ Intercepted message blocked - someone tried to $what") }
                    if (s.lastError == RecordError.REPLAY) {
                        g.blackbox.report(ThreatType.REPLAYED_MESSAGE, "Record counter already used in this session", l)
                    } else {
                        g.blackbox.report(ThreatType.TAMPERED_MESSAGE, "AES-GCM authentication tag did not verify", l)
                    }
                    return
                }
                s.inbox.trySend(plain)
            }
            // A protected watch refused us (wrong keys during a handshake or pairing): show the
            // intruder screen here. Never on a link that already finished a genuine handshake.
            F.BLACKBOX -> if (l.id !in authenticatedLinks) g.blackbox.accessDeniedHere(l.deviceName, l.address)
        }
    }

    private fun onHello(l: Link, f: ByteArray) {
        val partner = g.trust.partner.value
        if (l.isClient || partner == null) { reject(l); return }
        val hs = HandshakeServer(g.identity, partner.identityKey, partner.psk, pqIdentity())
        val resp = hs.onHello(f.copyOfRange(1, f.size)) ?: run { reject(l); return }
        serverHandshakes[l.id] = hs
        if (currentSession() == null) setPhase(Phase.AUTHENTICATING, "Verifying partner identity…")
        scope.launch { l.send(frame(F.HS_SERVER, resp)) }
    }

    private fun onFinish(l: Link, f: ByteArray) {
        val hs = serverHandshakes.remove(l.id) ?: return
        val keys = hs.onFinish(f.copyOfRange(1, f.size))
        if (keys == null && hs.failedOnlyPq) { Log.w(TAG, "suite V4 client signature mismatch"); reject(l); return }
        if (keys == null) {
            g.blackbox.report(ThreatType.AUTH_FAILED, "Handshake finish failed identity/PSK verification", l)
            sendBlackbox(l)
            if (currentSession() == null) setPhase(Phase.SEARCHING, "Rejected an unknown device")
            return
        }
        establish(l, keys)
    }

    private fun reject(l: Link) = scope.launch {
        l.send(frame(F.HS_REJECT)); delay(200); l.close()
    }

    /** Tells a device that failed authentication it was caught (its WristRadar, if any, shows BLACKBOX). */
    fun sendBlackbox(l: Link) = scope.launch {
        l.send(frame(F.BLACKBOX)); delay(300); l.close()
    }

    private fun onLinkClosed(l: Link) {
        waiters.remove(l.id)?.d?.complete(null)
        serverHandshakes.remove(l.id)
        pairing.onLinkClosed(l)
        if (session?.link === l) {
            val startedAt = session?.startedAt ?: now()
            session?.close()
            session = null
            sessionKem = null
            sessionSuite = null
            g.trust.markPartnerSeen()
            calls.onLinkLost()
            updater.onSessionDown()
            extras.onSessionDown()
            if (!renewing) extras.onLinkLost(now() - startedAt)
            typingJob?.cancel()
            _presence.update { it.copy(partnerTyping = false) }
            remoteBattery = null
            boostUntil = now() + BOOST_MS
            advertiser.stop()        // some stacks silently stop connectable adverts on connect
            if (renewing) { nextAttemptAt = 0; partnerFirstSeen = 0 }
            if (g.trust.partner.value != null) setPhase(Phase.SEARCHING, if (renewing) "Renewing session keys…" else "Link lost – reconnecting…")
            else setPhase(Phase.NOT_PAIRED, "")
            applyRadio()
        }
    }

    // =================================================================== periodic work

    private fun tick() {
        val t = now()
        if (nearbyMap.values.removeAll { t - it.lastSeen > 12_000 }) publishNearby()
        if (refreshDeadline != 0L && t > refreshDeadline && currentSession() == null && !connecting) {
            refreshDeadline = 0; failedAt = t
            setPhase(Phase.FAILED, "Partner not found. Check WristRadar is open on the other watch, Bluetooth is on and you are in range.")
        }
        val phase = _conn.value.phase
        if (phase == Phase.FAILED && failedAt != 0L && t - failedAt > 30_000 && g.trust.partner.value != null) {
            failedAt = 0; setPhase(Phase.SEARCHING, "Still looking…")
        }
        if (phase == Phase.DEVICE_FOUND && t - partnerLastSeen > 15_000 && !connecting) setPhase(Phase.SEARCHING, "Partner out of range")
        if (!running) return
        applyRadio()
        maybeConnect()
        checkPhone()
        if (currentSession() != null && t - g.trust.partnerLastSeen.value > 30_000) g.trust.markPartnerSeen(t)
        val s = currentSession()
        // Periodic session renewal: a brand-new hybrid handshake (fresh X25519 + ML-KEM keys) heals
        // the session even if its keys had leaked. Only the client side starts it, and only when idle.
        if (s != null) {
            val silentFor = t - lastInbound
            val waitingSince = oldestUnackedAt?.let { maxOf(it, s.startedAt) }
            val stuck = silentFor > STUCK_MS && waitingSince != null && t - waitingSince > STUCK_MS
            if (silentFor > DEAD_MS || stuck) {
                Log.w(TAG, "session silent for $silentFor ms (stuck=$stuck) - reconnecting")
                s.close()
                return
            }
            scope.launch { oldestUnackedAt = messenger.oldestUnackedTextAt() }
        }
        // Upgrade a 768 session to ML-KEM-1024 as soon as both sides support it (client starts it, when idle).
        val canUpgrade = when (sessionSuite) {
            com.wristradar.crypto.Suite.V2 -> updater.knownPartnerCode >= KEM1024_SINCE
            com.wristradar.crypto.Suite.V3 -> updater.knownPartnerCode >= SUITE_V4_SINCE && g.trust.partnerPq != null
            else -> false
        }
        if (s != null && s.link.isClient && canUpgrade && !strongKemFailed &&
            t - lastActivity > 15_000 && !messenger.busy && !calls.state.value.live
        ) {
            renewing = true
            Log.i(TAG, "upgrading session from $sessionSuite")
            s.close()
            return
        }
        if (s != null && s.link.isClient && t - s.startedAt > RENEW_MS && t - lastActivity > 120_000 && !messenger.busy) {
            renewing = true
            Log.i(TAG, "renewing session keys")
            s.close()
            return
        }
        // Battery: chat at full speed while the app is open or you're actively talking, otherwise
        // stretch the Bluetooth connection interval and send status less often.
        val up = updater.state.value
        val active = g.uiVisibility.appVisible || calls.state.value.live || messenger.busy || t - lastActivity < ACTIVE_MS ||
            up.sendingPct != null || up.receivingPct != null
        (s?.link as? ClientLink)?.let { cl ->
            val want = if (active) android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED else android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            if (want != linkPriority && !messenger.busy) { linkPriority = want; cl.setPriority(want) }
        }
        if (s == null || messenger.busy) linkPriority = -1   // a transfer sets its own priority; re-apply after
        if (s != null && t - lastStatusSent > (if (active) STATUS_MS else STATUS_IDLE_MS)) {
            lastStatusSent = t
            scope.launch {
                val rssi = (s.link as? ClientLink)?.readRssi()
                // Max Range: drop to the Coded PHY (long range) when the signal gets weak, back to fast PHY when close.
                if (rssi != null && g.settings.current.range == RangeMode.MAX) (s.link as? ClientLink)?.adaptPhy(rssi)
                if (rssi != null) { partnerEst.add(rssi); publishDistance() }
                partnerEst.smoothedRssi?.let { extras.onSignal(it.roundToInt()) }
                messenger.sendStatus(s, rssi)
                messenger.retryTick(s)
            }
        } else if (s == null && t - partnerLastSeen > 20_000 && _conn.value.distanceM != null) {
            partnerEst.reset(); publishDistance()
        }
    }

    /** Bluetooth 5 long range (LE Coded PHY) available on this watch. */
    val longRangeSupported: Boolean get() = advertiser.longRangeSupported

    /** Saves the current partner signal as the "1 metre" reference. Returns the value or null. */
    fun calibrateDistance(): Int? {
        val r = partnerEst.smoothedRssi?.roundToInt() ?: return null
        g.settings.update { it.copy(rssiAt1m = r) }
        publishDistance()
        return r
    }

    fun onRemoteStatus(battery: Int?, rssi: Int?) = scope.launch {
        remoteBattery = battery
        if (rssi != null && currentSession()?.link?.isClient == false) partnerEst.add(rssi)
        publishDistance()
    }

    // =================================================================== user actions

    fun acquireDiscovery() = scope.launch {
        if (discoveryRefs++ == 0) {
            myDiscoveryId = Random.nextInt(1, Int.MAX_VALUE)
            nearbyMap.clear(); publishNearby()
            if (!running) start().join()
            applyRadio()
        }
    }

    fun releaseDiscovery() = scope.launch {
        delay(1_500)   // allow screen-to-screen navigation without tearing down
        if (discoveryRefs > 0 && --discoveryRefs == 0) {
            nearbyMap.clear(); publishNearby()
            if (pairing.state.value.active) pairing.cancel() else pairing.dismissResult()
            applyRadio()
            if (g.trust.partner.value == null) stopIfIdle()
        }
    }

    fun requestPair(discoveryId: Int) = scope.launch {
        val e = nearbyMap[discoveryId]
        if (e == null) { pairing.reportError("That watch is no longer nearby."); return@launch }
        pairing.start(discoveryId, e.device, e.name ?: "Watch")
    }

    fun pairAccept() = scope.launch { pairing.accept() }
    fun pairDecline() = scope.launch { pairing.decline() }
    fun pairConfirm(match: Boolean) = scope.launch { pairing.confirm(match) }
    fun pairCancel() = scope.launch { pairing.cancel() }
    fun pairDismiss() = scope.launch { pairing.dismissResult() }

    /** Called by [PairingController] on the engine thread once both users confirmed. */
    fun onPaired(link: Link) {
        tokenWindow = -1
        failCount = 0
        WristRadarService.start(ctx)
        if (link.isClient && !link.closed) scope.launch { handshakeAsClient(link) }
        else setPhase(Phase.AUTHENTICATING, "Securing session…")
        applyRadio()
    }

    fun refresh() = scope.launch {
        if (!running) start().join()
        if (!running) return@launch
        if (g.trust.partner.value == null) { setPhase(Phase.NOT_PAIRED, "Pair with a watch first"); return@launch }
        setPhase(Phase.REFRESHING, "Refreshing connection…")
        val s = currentSession()
        if (s != null) {
            val ok = withTimeoutOrNull(5_000) { messenger.sendStatus(s, (s.link as? ClientLink)?.readRssi()) } == true
            if (ok) { delay(400); setPhase(Phase.CONNECTED, "Connection healthy"); return@launch }
            s.close()   // stale link; onLinkClosed will follow
            delay(300)
        }
        failCount = 0; nextAttemptAt = 0
        boostUntil = now() + BOOST_MS
        scanner.stop(); advertiser.stop()
        delay(300)
        if (!server.isRunning) server.start()
        refreshDeadline = now() + 30_000
        setPhase(Phase.SEARCHING, "Looking for your partner…")
        applyRadio()
        maybeConnect()
    }

    fun pushProfile() = scope.launch { currentSession()?.let { messenger.sendProfile(it) } }

    fun unpair() = scope.launch {
        currentSession()?.let { s -> withTimeoutOrNull(3_000) { messenger.sendUnpair(s) } }
        localUnpair("Unpaired")
    }

    fun onRemoteUnpair() = scope.launch {
        localUnpair("Your partner removed the pairing")
        g.notifier.showInfo("Pairing removed", "Your partner unpaired. Pair again to chat.")
    }

    private suspend fun localUnpair(msg: String) {
        session?.close(); session = null
        connecting = false
        g.trust.clear()
        g.profile.clearPartner()
        g.messages.clearAll()
        g.notifier.cancelMessages()
        partnerDevice = null; partnerEst.reset(); remoteBattery = null
        tokenWindow = -1
        _conn.value = ConnectionState(Phase.NOT_PAIRED, msg)
        applyRadio()
        WristRadarService.stop(ctx)
    }

    // =================================================================== discovery profile fetch

    private fun maybeFetchProfiles() {
        if (fetchingProfile || !discoveryActive || pairing.state.value.active) return
        val e = nearbyMap.values.filter { !it.fetched && it.fetchFails < 3 }
            .maxByOrNull { it.est.smoothedRssi ?: -127.0 } ?: return
        fetchingProfile = true
        scope.launch {
            try {
                val link = openClientLink(e.device, attempts = 2, timeoutMs = 7_000) ?: run { e.fetchFails++; return@launch }   // quick: profile peek
                val resp = request(link, frame(F.PROFILE_REQ), setOf(F.PROFILE_RESP), 6_000)
                link.close()
                if (resp == null) { e.fetchFails++; return@launch }
                val r = In(resp, 1)
                val did = r.i()
                val name = r.str(200)
                val av = r.bytes(30_000)
                if (did != e.id) { e.fetchFails++; return@launch }
                e.name = ProfileStore.sanitizeName(name)
                e.avatar = av?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                e.fetched = true
                publishNearby()
            } catch (ex: Exception) {
                e.fetchFails++
            } finally {
                fetchingProfile = false
                if (discoveryActive) maybeFetchProfiles()
            }
        }
    }

    // =================================================================== state publishing

    private fun publishNearby() {
        _nearby.value = nearbyMap.values
            .sortedByDescending { it.est.smoothedRssi ?: -127.0 }
            .map { NearbyUser(it.id, it.name, it.avatar, it.est.smoothedRssi?.roundToInt() ?: -127, it.est.distanceMetres(g.settings.current.rssiAt1m), it.lastSeen) }
    }

    private var lastDistancePublish = 0L

    /** Throttled + rounded so the UI isn't redrawn for every Bluetooth scan result. */
    private fun publishDistance() {
        val t = now()
        val rssi = partnerEst.smoothedRssi?.roundToInt()
        val d = partnerEst.distanceMetres(g.settings.current.rssiAt1m)?.let { Math.round(it * 10) / 10.0 }
        val cur = _conn.value
        if (cur.rssi == rssi && cur.distanceM == d && cur.remoteBattery == remoteBattery) return
        val important = (cur.distanceM == null) != (d == null) || cur.remoteBattery != remoteBattery
        if (!important && t - lastDistancePublish < 250) return
        lastDistancePublish = t
        _conn.update { it.copy(rssi = rssi, distanceM = d, remoteBattery = remoteBattery) }
    }

    private fun setPhase(p: Phase, detail: String) {
        val was = _conn.value.phase
        _conn.update { it.copy(phase = p, detail = detail) }
        if ((was == Phase.CONNECTED) != (p == Phase.CONNECTED)) com.wristradar.glance.Glance.refresh(ctx)
    }

    private fun baselinePhase() = if (g.trust.partner.value == null) Phase.NOT_PAIRED else Phase.STANDBY

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "WristRadar"
        private const val TICK_MS = 5_000L
        private const val STATUS_MS = 15_000L
        private const val STATUS_IDLE_MS = 45_000L
        /** No valid message from the friend for this long = dead link (they send status every <= 45 s). */
        private const val DEAD_MS = 130_000L
        /** A sent text unanswered AND nothing heard for this long = stuck link. */
        private const val STUCK_MS = 30_000L
        private const val SIMULTANEOUS_MS = 8_000L
        /** First versionCode that speaks the ML-KEM-1024 handshake. */
        const val KEM1024_SINCE = 17
        /** First versionCode with suite V4 (ML-DSA-87 + SHA-384) and PQ_ID. */
        const val SUITE_V4_SINCE = 18
        private const val ACTIVE_MS = 60_000L
        private const val SEARCH_HARD_MS = 120_000L
        private const val BOOST_MS = 60_000L
        private const val RENEW_MS = 3 * 60 * 60 * 1000L
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }
}
