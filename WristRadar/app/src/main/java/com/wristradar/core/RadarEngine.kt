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
import com.wristradar.crypto.MlKem
import com.wristradar.crypto.RecordError
import com.wristradar.crypto.SecureChannel
import com.wristradar.crypto.SessionKeys
import com.wristradar.crypto.Suite
import com.wristradar.data.Partner
import com.wristradar.data.ProfileStore
import com.wristradar.data.RangeMode
import com.wristradar.protocol.A
import com.wristradar.protocol.F
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import com.wristradar.protocol.frame
import com.wristradar.security.ThreatType
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
 * Orchestrates radio policy, links, discovery, pairing and the authenticated sessions.
 * All mutable state is confined to a single-threaded dispatcher; BLE callbacks hop onto it.
 *
 * MK19: one [PeerLink] per paired contact, so a watch can be connected to several watches at once.
 * The first contact is the "primary" one; its link drives [connection] and [presence] exactly as
 * before, so every 1:1 screen keeps working. Per contact, the rules are unchanged: the watch with
 * the lower identity key connects first, duplicate links are dropped with SESSION_DROP, dead links
 * are noticed by liveness checks, and sessions re-key and upgrade when idle.
 */
@SuppressLint("MissingPermission")
@OptIn(ExperimentalCoroutinesApi::class)
class RadarEngine(val ctx: Context, private val g: AppGraph) {

    /** One background task failing must never take the whole app down (it's logged instead). */
    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "background task failed", e) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + crashGuard)

    private val _conn = MutableStateFlow(ConnectionState(if (g.trust.partner.value == null) Phase.NOT_PAIRED else Phase.STANDBY))
    /** The PRIMARY contact's link (all the classic 1:1 screens use this). */
    val connection: StateFlow<ConnectionState> = _conn.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    /** Every contact's link state, by contact id. */
    val peers: StateFlow<Map<String, PeerStatus>> = _peers.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbyUser>>(emptyList())
    val nearby: StateFlow<List<NearbyUser>> = _nearby.asStateFlow()

    val pairing = PairingController(g, this)
    val messenger = Messenger(g, this)
    val calls = com.wristradar.audio.CallManager(ctx, g, this)
    val updater = Updater(ctx, g, this)
    val walk = WalkTracker(ctx, g.settings)
    val extras = FunHub(ctx, g, this)
    val groups = GroupHub(g, this)

    private val _presence = MutableStateFlow(Presence())
    val presence: StateFlow<Presence> = _presence.asStateFlow()
    private var typingJob: Job? = null
    private var lastPhoneCheck = 0L
    private var pendingNotice: String? = null
    private val versionPrefs = ctx.getSharedPreferences("wr_peer_versions", Context.MODE_PRIVATE)

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
    private var tickCount = 0L

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

    // =================================================================== contacts' links

    /** Everything about one contact's connection. */
    inner class PeerLink(var contact: Partner) {
        val id: String get() = contact.id
        var session: SecureSession? = null
        var connecting = false
        var device: BluetoothDevice? = null
        var lastSeen = 0L
        var firstSeen = 0L
        val est = DistanceEstimator()
        var remoteBattery: Int? = null
        var failCount = 0
        var nextAttemptAt = 0L
        var refreshDeadline = 0L
        var failedAt = 0L
        var lastStatusSent = 0L
        var searchingSince = 0L
        var linkPriority = -1
        @Volatile var oldestUnackedAt: Long? = null
        var tokenWindow = -1L
        var tokens: List<ByteArray> = emptyList()
        var renewing = false
        @Volatile var lastActivity = 0L
        @Volatile var lastInbound = 0L
        var kem: MlKem.Level? = null
        var suite: Suite? = null
        var strongKemFailed = false
        var strongFailedAtCode = -1
        var pqSentFor: SecureSession? = null
        var typing = false
        var typingJob: Job? = null
        val primary: Boolean get() = g.trust.isPrimary(id)
        val name: String get() = (if (primary) g.profile.partnerName.value else g.trust.contactName(id)) ?: "Friend"
        fun alive(): SecureSession? = session?.takeIf { !it.link.closed }

        /** App version this contact's watch last reported. */
        var knownCode: Int
            get() = if (primary) updater.knownPartnerCode else versionPrefs.getInt(id, -1)
            set(v) { if (!primary) versionPrefs.edit().putInt(id, v).apply() }
    }

    private val peerLinks = LinkedHashMap<String, PeerLink>()

    /** Keeps one [PeerLink] per contact (contacts can be added or removed at any time). */
    private fun syncPeers() {
        val contacts = g.trust.contacts.value
        val ids = contacts.map { it.id }.toSet()
        peerLinks.keys.filter { it !in ids }.forEach { gone ->
            peerLinks.remove(gone)?.let { pl -> pl.session?.close(); pl.session = null }
        }
        contacts.forEach { c -> peerLinks.getOrPut(c.id) { PeerLink(c) }.contact = c }
        // keep the primary first
        val primaryId = g.trust.partner.value?.id
        if (primaryId != null && peerLinks.keys.firstOrNull() != primaryId) {
            val p = peerLinks.remove(primaryId)
            val rest = LinkedHashMap(peerLinks)
            peerLinks.clear()
            if (p != null) peerLinks[primaryId] = p
            peerLinks.putAll(rest)
        }
    }

    private fun primaryPeer(): PeerLink? { syncPeers(); return g.trust.partner.value?.id?.let { peerLinks[it] } }
    private fun allPeers(): Collection<PeerLink> { syncPeers(); return peerLinks.values }
    fun peer(id: String): PeerLink? { syncPeers(); return peerLinks[id] }

    /** The contact a session belongs to. */
    fun peerOf(s: SecureSession): PeerLink? = allPeers().firstOrNull { it.session === s }
    private fun peerOfLink(l: Link): PeerLink? = allPeers().firstOrNull { it.session?.link === l }

    /** The primary contact's live session (all classic 1:1 features). */
    fun currentSession(): SecureSession? = primaryPeer()?.alive()

    /** A contact's live session. */
    fun sessionFor(id: String): SecureSession? = peer(id)?.alive()

    private val authenticatedLinks = HashSet<Int>()

    /** Last time an app message moved over the primary session (used to renew keys only when idle). */
    var lastActivity: Long
        get() = primaryPeer()?.lastActivity ?: 0L
        set(v) { primaryPeer()?.lastActivity = v }

    /** KEM strength / suite of the primary session (null = not connected). */
    val sessionKem: MlKem.Level? get() = primaryPeer()?.takeIf { it.alive() != null }?.kem
    val sessionSuite: Suite? get() = primaryPeer()?.takeIf { it.alive() != null }?.suite

    /** My ML-DSA-87 identity + a contact's, or null while we don't have theirs yet. */
    private fun pqIdentity(p: PeerLink): com.wristradar.crypto.PqIdentity? {
        val theirs = g.trust.contactPq(p.id) ?: return null
        return runCatching { com.wristradar.crypto.PqIdentity(g.identity.pq, theirs) }.getOrNull()
    }

    /** Sends my post-quantum identity once per session to a contact whose app understands it. */
    private fun maybeSendPqIdentity(p: PeerLink) {
        val s = p.alive() ?: return
        if (p.pqSentFor === s || p.knownCode < SUITE_V4_SINCE) return
        p.pqSentFor = s
        scope.launch(Dispatchers.Default) {
            val kp = g.identity.pq
            val pop = kp.sign("WR-PQID".toByteArray() + g.identity.publicKey)
            s.send(A.PQ_ID, Out().bytes(kp.publicKey).bytes(pop).build())
        }
    }

    /** A contact's post-quantum identity arrived over our authenticated session. */
    fun onPeerPqIdentity(s: SecureSession, p: ByteArray) {
        val peer = peerOf(s) ?: return
        val r = In(p)
        val pub = r.bytes(com.wristradar.crypto.MlDsa.PUBLIC_KEY_SIZE + 16) ?: return
        val pop = r.bytes(com.wristradar.crypto.MlDsa.SIGNATURE_SIZE + 16) ?: return
        if (!com.wristradar.crypto.MlDsa.verify(pub, pop, "WR-PQID".toByteArray() + peer.contact.identityKey)) {
            Log.w(TAG, "post-quantum identity: bad proof of possession"); return
        }
        if (!g.trust.saveContactPq(peer.id, pub)) Log.w(TAG, "post-quantum identity changed without re-pairing - ignored")
    }

    /** A contact's app version arrived (primary: also tracked by the Updater). */
    fun onPeerVersion(s: SecureSession, code: Int) {
        val p = peerOf(s) ?: return
        if (!p.primary) p.knownCode = code
        if (code >= KEM1024_SINCE && code != p.strongFailedAtCode) p.strongKemFailed = false
        maybeSendPqIdentity(p)
        if (code >= GROUPS_SINCE) scope.launch { groups.onPeerUp(p.id) }
    }

    /** Typing indicator from any contact (primary also drives the classic presence). */
    fun onPeerTyping(s: SecureSession, typing: Boolean) {
        val p = peerOf(s) ?: return
        if (p.primary) onPartnerTyping(typing)
        p.typingJob?.cancel()
        p.typing = typing
        if (typing) p.typingJob = scope.launch { delay(15_000); p.typing = false; publishPeers() }
        publishPeers()
    }

    private class Waiter(val types: Set<Byte>, val d: CompletableDeferred<ByteArray?>)
    private val waiters = HashMap<Int, Waiter>()
    private val serverHandshakes = HashMap<Int, Pair<String, HandshakeServer>>()
    /** Which contact a server-side link said it is (HS_WHO), by link id. */
    private val whoByLink = HashMap<Int, String>()

    // =================================================================== lifecycle

    fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start() = scope.launch {
        launch(Dispatchers.IO) { runCatching { g.messages.migrateBodies() } }   // one-time, background
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
        if (g.trust.contacts.value.isEmpty() && !discoveryActive && !pairing.state.value.active) shutdown()
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
        allPeers().forEach { p -> p.session?.close(); p.session = null; p.connecting = false }
        connectBusy = false
        waiters.values.forEach { it.d.complete(null) }; waiters.clear()
        serverHandshakes.clear(); whoByLink.clear()
        publishPeers()
    }

    // =================================================================== radio policy

    private var boostUntil = 0L
    private var randomToken = Crypto.random(8)
    private var randomTokenWindow = -1L

    private fun applyRadio() {
        if (!running || adapter?.isEnabled != true) { scanner.stop(); advertiser.stop(); return }
        val t = now()
        val peers = allPeers()
        val waiting = peers.filter { it.alive() == null }
        peers.forEach { p -> if (p.alive() != null) p.searchingSince = 0L else if (p.searchingSince == 0L) p.searchingSince = t }
        val searchingLong = waiting.isNotEmpty() && waiting.all { t - it.searchingSince > SEARCH_HARD_MS }
        // Every contact's rolling tokens, to recognise them in scan results.
        val window = AdvertPayload.currentWindow(t)
        peers.forEach { p ->
            if (window != p.tokenWindow) {
                val k = p.contact.advertKeyFor(p.contact.identityKey)
                p.tokens = listOf(window - 1, window, window + 1).map { AdvertPayload.token(k, it) }
                p.tokenWindow = window
            }
        }
        val wantAdvert = discoveryActive || pairing.state.value.active || waiting.isNotEmpty()
        if (wantAdvert) {
            // One advert at a time: rotate between the contacts we're still looking for.
            val target = if (waiting.isEmpty()) null else waiting.elementAt(((tickCount) % waiting.size).toInt())
            val token = if (target != null) {
                AdvertPayload.token(target.contact.advertKeyFor(g.identity.publicKey), window)
            } else {
                if (window != randomTokenWindow) { randomToken = Crypto.random(8); randomTokenWindow = window }
                randomToken
            }
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
            waiting.isNotEmpty() -> when {
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
        for (p in allPeers()) {
            if (p.tokens.any { it.contentEquals(payload.token) }) { onPeerSeen(p, r.device, r.rssi); break }
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

    private fun onPeerSeen(p: PeerLink, device: BluetoothDevice, rssi: Int) {
        val t = now()
        p.est.add(rssi)
        p.device = device
        if (t - p.lastSeen > 15_000) p.firstSeen = t
        p.lastSeen = t
        if (p.alive() == null) {
            publishDistance(p)
            if (p.primary && !p.connecting && _conn.value.phase in setOf(Phase.SEARCHING, Phase.REFRESHING, Phase.FAILED, Phase.STANDBY)) {
                setPhase(Phase.DEVICE_FOUND, "Partner in range")
            }
            maybeConnect(p)
        }
    }

    // =================================================================== contact connection

    /** One outgoing connection attempt at a time keeps the Bluetooth stack happy. */
    private var connectBusy = false

    private fun maybeConnect(p: PeerLink) {
        if (!running || p.alive() != null || p.connecting || connectBusy || pairing.state.value.active) return
        val device = p.device ?: return
        val t = now()
        if (t - p.lastSeen > 10_000 || t < p.nextAttemptAt) return
        val iAmLower = Crypto.compare(g.identity.publicKey, p.contact.identityKey) < 0
        if (!iAmLower && t - p.firstSeen < 12_000 && t > boostUntil) return
        p.connecting = true
        connectBusy = true
        publishPeers()
        scope.launch {
            if (p.primary) setPhase(Phase.CONNECTING, "Opening link…")
            val link = openClientLink(device)
            if (link == null) { connectBusy = false; onConnectFailed(p, "Could not connect"); return@launch }
            connectBusy = false
            handshakeAsClient(p, link)
        }
    }

    private fun whoTag(psk: ByteArray, nonce: ByteArray) = Crypto.hmac(psk, "WR-WHO".toByteArray() + nonce).copyOf(16)

    private suspend fun handshakeAsClient(p: PeerLink, link: Link) {
        p.connecting = true
        val contact = p.contact
        if (p.primary) setPhase(Phase.AUTHENTICATING, "Verifying partner identity…")
        // Say which contact we are (older watches simply ignore this frame).
        val nonce = Crypto.random(16)
        link.send(frame(F.HS_WHO, nonce + whoTag(contact.psk, nonce)))
        // ML-KEM-1024 once we know the contact's app understands it; otherwise (or if that just
        // failed) the ML-KEM-768 handshake every version speaks.
        val code = p.knownCode
        val strong = code >= KEM1024_SINCE && !p.strongKemFailed
        // Top suites (ML-DSA-87 + SHA-384, then per-message keys) once we hold the contact's PQ identity.
        val pq = if (strong && code >= SUITE_V4_SINCE) pqIdentity(p) else null
        val hc = HandshakeClient(g.identity, contact.identityKey, contact.psk,
            if (strong) MlKem.Level.ML_KEM_1024 else MlKem.Level.ML_KEM_768, pq,
            perMessage = code >= SUITE_V5_SINCE)
        val resp = request(link, frame(F.HS_HELLO, hc.hello()), setOf(F.HS_SERVER, F.HS_REJECT, F.BLACKBOX), 10_000)
        if (resp != null && resp[0] == F.BLACKBOX) {
            link.close()
            g.blackbox.accessDeniedHere(link.deviceName, link.address)
            onConnectFailed(p, "Access denied by the other watch")
            return
        }
        if (resp == null || resp[0] == F.HS_REJECT) {
            if (strong) { p.strongKemFailed = true; p.strongFailedAtCode = code }   // retry with ML-KEM-768
            link.close()
            onConnectFailed(p, if (resp == null) "No response from partner" else "Partner refused the session (pairing removed?)")
            return
        }
        val res = hc.onServer(resp.copyOfRange(1, resp.size))
        if (res == null && hc.failedOnlyPq) {
            Log.w(TAG, "suite V4 signature mismatch - falling back")
            p.strongKemFailed = true; p.strongFailedAtCode = code
            link.close(); onConnectFailed(p, "Retrying with compatible encryption"); return
        }
        if (res == null) {
            // It advertised our contact's secret rolling token yet cannot prove the contact's identity.
            g.blackbox.report(ThreatType.IMPERSONATION, "Handshake response failed signature/PSK verification", link)
            sendBlackbox(link)
            onConnectFailed(p, "Authentication failed: device is not your trusted partner")
            return
        }
        if (!link.send(frame(F.HS_FINISH, res.first))) { onConnectFailed(p, "Connection lost"); return }
        p.connecting = false
        establish(p, link, res.second)
    }

    private fun onConnectFailed(p: PeerLink, msg: String) {
        p.connecting = false
        p.failCount++
        // Retry quickly: at the edge of range a reconnect often works a few seconds later.
        p.nextAttemptAt = now() + minOf(20_000L, 3_000L * p.failCount)
        if (p.primary) {
            if (p.refreshDeadline != 0L || p.failCount >= 3) { p.failedAt = now(); setPhase(Phase.FAILED, msg) }
            else setPhase(Phase.SEARCHING, "$msg – retrying")
        }
        publishPeers()
    }

    private fun establish(p: PeerLink, link: Link, keys: SessionKeys) {
        val existing = p.alive()
        if (existing != null && existing.link === link) {
            // The contact restarted and reconnected on the very same connection: a fresh handshake
            // on our session's link. Swap in the new keys and keep the link (never drop it).
            Log.i(TAG, "contact re-keyed our session link")
            p.session = null
            existing.closeKeepLink()
        } else if (existing != null) {
            val iAmLower = Crypto.compare(g.identity.publicKey, p.contact.identityKey) < 0
            // Both watches connecting at the same moment: keep the deterministic preferred link.
            // But if our session is older than a few seconds, the contact only reconnected because
            // THEIR side of it died - our old session is a zombie, so the new link always wins.
            val simultaneous = now() - existing.startedAt < SIMULTANEOUS_MS
            if (simultaneous && link.isClient != iAmLower) { dropLink(link); return }
            Log.i(TAG, "replacing session (age ${now() - existing.startedAt} ms) with a new link")
            p.session = null
            dropLink(existing.link)
            existing.closeKeepLink()
        }
        val s = SecureSession(link, SecureChannel(keys, link.isClient))
        p.session = s
        p.kem = keys.kem
        p.suite = keys.suite
        p.lastInbound = now()
        authenticatedLinks += link.id
        partnerAddresses[link.address] = now()
        p.renewing = false
        p.connecting = false
        p.failCount = 0; p.nextAttemptAt = 0; p.refreshDeadline = 0
        if (p.primary) {
            g.trust.markConnected()
            g.trust.markPartnerSeen()
            checkPhone(force = true)
            pendingNotice?.let { n -> pendingNotice = null; scope.launch { shareSecurityNotice(n) } }
            setPhase(Phase.CONNECTED, "End-to-end encrypted")
            updater.prepareSession()
        }
        publishPeers()
        applyRadio()
        WristRadarService.start(ctx)
        scope.launch {
            for (m in s.inbox) {
                if (m.isEmpty()) continue
                if (m[0] != A.STATUS) p.lastActivity = System.currentTimeMillis()
                try { messenger.handle(s, m[0], m.copyOfRange(1, m.size)) }
                catch (e: Exception) { Log.w(TAG, "bad app message type=${m[0]}: ${e.javaClass.simpleName}") }
            }
        }
        scope.launch {
            if (p.primary) { updater.onSessionUp(s); messenger.onSessionUp(s); extras.onSessionUp(s) }
            else { messenger.onPeerSessionUp(s); updater.sendVersionTo(s) }
        }
    }

    // =================================================================== links & frames

    suspend fun openClientLink(device: BluetoothDevice, attempts: Int = 4, timeoutMs: Long = 20_000): ClientLink? {
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
            F.PAIR_COMMIT, F.PAIR_RESPONSE, F.PAIR_RESPONSE3, F.PAIR_REVEAL, F.PAIR_CONFIRM, F.PAIR_ABORT -> pairing.onFrame(l, f)
            F.HS_WHO -> onWho(l, f)
            F.HS_HELLO -> onHello(l, f)
            F.HS_FINISH -> onFinish(l, f)
            F.SECURE -> {
                val p = peerOfLink(l)
                val s = p?.session
                if (p == null || s == null) {
                    // Records on a link that isn't a session: almost always a contact still using a
                    // connection we've dropped (both connected at once, or we just restarted). They
                    // can't be decrypted here, so they're harmless; real break-in attempts are caught
                    // by the handshake/pairing checks. Ask the other side to drop it, so the two
                    // watches reconnect on one link within seconds instead of waiting for a timeout.
                    if (droppedLinks.add(l.id)) { Log.i(TAG, "records on a non-session link - asking the other side to drop it"); dropLink(l) }
                    return
                }
                val plain = s.open(f.copyOfRange(1, f.size))
                if (plain != null) p.lastInbound = now()
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
            // The other watch is not using this connection: if it was our session, let it go.
            F.SESSION_DROP -> {
                peerOfLink(l)?.let { p -> Log.i(TAG, "contact dropped our session link - switching"); p.session?.close() }
                if (l is com.wristradar.ble.ServerLink) l.closeSoft() else l.close()
            }
        }
    }
    private var rejectedRecords = 0

    /** HS_WHO: find the contact whose pairing secret produced this tag. */
    private fun onWho(l: Link, f: ByteArray) {
        if (l.isClient || f.size != 1 + 32) return
        val nonce = f.copyOfRange(1, 17); val tag = f.copyOfRange(17, 33)
        val who = allPeers().firstOrNull { Crypto.ctEquals(whoTag(it.contact.psk, nonce), tag) }
        if (who != null) whoByLink[l.id] = who.id
    }

    private fun onHello(l: Link, f: ByteArray) {
        if (l.isClient) { reject(l); return }
        // Older apps don't send HS_WHO: they can only be the primary contact.
        val p = whoByLink[l.id]?.let { peer(it) } ?: primaryPeer() ?: run { reject(l); return }
        if (p.session?.link === l) Log.i(TAG, "handshake on our session link - contact restarted")
        val hs = HandshakeServer(g.identity, p.contact.identityKey, p.contact.psk, pqIdentity(p))
        val resp = hs.onHello(f.copyOfRange(1, f.size)) ?: run { reject(l); return }
        serverHandshakes[l.id] = p.id to hs
        if (p.primary && p.alive() == null) setPhase(Phase.AUTHENTICATING, "Verifying partner identity…")
        scope.launch { l.send(frame(F.HS_SERVER, resp)) }
    }

    private fun onFinish(l: Link, f: ByteArray) {
        val (pid, hs) = serverHandshakes.remove(l.id) ?: return
        val p = peer(pid) ?: return
        val keys = hs.onFinish(f.copyOfRange(1, f.size))
        if (keys == null && hs.failedOnlyPq) { Log.w(TAG, "suite V4 client signature mismatch"); reject(l); return }
        if (keys == null) {
            g.blackbox.report(ThreatType.AUTH_FAILED, "Handshake finish failed identity/PSK verification", l)
            sendBlackbox(l)
            if (p.primary && p.alive() == null) setPhase(Phase.SEARCHING, "Rejected an unknown device")
            return
        }
        establish(p, l, keys)
    }

    private fun reject(l: Link) = scope.launch {
        l.send(frame(F.HS_REJECT)); delay(200); l.close()
    }

    /** Tells a device that failed authentication it was caught (its WristRadar, if any, shows BLACKBOX). */
    fun sendBlackbox(l: Link) = scope.launch {
        l.send(frame(F.BLACKBOX)); delay(300); l.close()
    }

    /** Addresses (with time) that completed a genuine handshake. */
    private val partnerAddresses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val droppedLinks = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    /** Tells the other watch we're not using this link, then closes it. */
    private fun dropLink(l: Link) = scope.launch {
        if (!l.closed) { runCatching { l.send(frame(F.SESSION_DROP)) }; delay(150) }
        if (l is com.wristradar.ble.ServerLink) l.closeSoft() else l.close()
    }

    private fun onLinkClosed(l: Link) {
        waiters.remove(l.id)?.d?.complete(null)
        serverHandshakes.remove(l.id)
        whoByLink.remove(l.id)
        pairing.onLinkClosed(l)
        val p = peerOfLink(l) ?: return
        val startedAt = p.session?.startedAt ?: now()
        p.session?.close()
        p.session = null
        p.kem = null; p.suite = null
        p.remoteBattery = null
        p.typing = false
        calls.onPeerLinkLost(p.id)
        if (p.primary) {
            g.trust.markPartnerSeen()
            updater.onSessionDown()
            extras.onSessionDown()
            if (!p.renewing) extras.onLinkLost(now() - startedAt)
            typingJob?.cancel()
            _presence.update { it.copy(partnerTyping = false) }
            setPhase(Phase.SEARCHING, if (p.renewing) "Renewing session keys…" else "Link lost – reconnecting…")
        }
        boostUntil = now() + BOOST_MS
        advertiser.stop()        // some stacks silently stop connectable adverts on connect
        if (p.renewing) { p.nextAttemptAt = 0; p.firstSeen = 0 }
        publishPeers()
        applyRadio()
    }

    // =================================================================== periodic work

    private fun tick() {
        tickCount++
        val t = now()
        if (nearbyMap.values.removeAll { t - it.lastSeen > 12_000 }) publishNearby()
        val pp = primaryPeer()
        if (pp != null) {
            if (pp.refreshDeadline != 0L && t > pp.refreshDeadline && pp.alive() == null && !pp.connecting) {
                pp.refreshDeadline = 0; pp.failedAt = t
                setPhase(Phase.FAILED, "Partner not found. Check WristRadar is open on the other watch, Bluetooth is on and you are in range.")
            }
            val phase = _conn.value.phase
            if (phase == Phase.FAILED && pp.failedAt != 0L && t - pp.failedAt > 30_000) {
                pp.failedAt = 0; setPhase(Phase.SEARCHING, "Still looking…")
            }
            if (phase == Phase.DEVICE_FOUND && t - pp.lastSeen > 15_000 && !pp.connecting) setPhase(Phase.SEARCHING, "Partner out of range")
        }
        if (!running) return
        applyRadio()
        allPeers().forEach { maybeConnect(it) }
        checkPhone()
        if (pp?.alive() != null && t - g.trust.partnerLastSeen.value > 30_000) g.trust.markPartnerSeen(t)
        for (p in allPeers().toList()) tickPeer(p, t)
        publishPeers()
    }

    private fun tickPeer(p: PeerLink, t: Long) {
        val s = p.alive()
        if (s != null) {
            val silentFor = t - p.lastInbound
            val waitingSince = p.oldestUnackedAt?.let { maxOf(it, s.startedAt) }
            val stuck = silentFor > STUCK_MS && waitingSince != null && t - waitingSince > STUCK_MS
            if (silentFor > DEAD_MS || stuck) {
                Log.w(TAG, "session silent for $silentFor ms (stuck=$stuck) - reconnecting")
                s.close()
                return
            }
            scope.launch { p.oldestUnackedAt = messenger.oldestUnackedTextAt(messenger.convOf(p.id)) }
        }
        val code = p.knownCode
        // Upgrade to the strongest suite both sides support (client starts it, when idle).
        val pqKnown = g.trust.contactPq(p.id) != null
        val canUpgrade = when (p.suite) {
            Suite.V2 -> code >= KEM1024_SINCE
            Suite.V3 -> code >= SUITE_V4_SINCE && pqKnown
            Suite.V4 -> code >= SUITE_V5_SINCE && pqKnown
            else -> false
        }
        val callLive = calls.state.value.live
        if (s != null && s.link.isClient && canUpgrade && !p.strongKemFailed &&
            t - p.lastActivity > 15_000 && !messenger.busy && !callLive
        ) {
            p.renewing = true
            Log.i(TAG, "upgrading session from ${p.suite}")
            s.close()
            return
        }
        if (s != null && s.link.isClient && t - s.startedAt > RENEW_MS && t - p.lastActivity > 120_000 && !messenger.busy && !callLive) {
            p.renewing = true
            Log.i(TAG, "renewing session keys")
            s.close()
            return
        }
        // Battery: chat at full speed while the app is open or you're actively talking, otherwise
        // stretch the Bluetooth connection interval and send status less often.
        val up = updater.state.value
        val active = g.uiVisibility.appVisible || callLive || messenger.busy || t - p.lastActivity < ACTIVE_MS ||
            (p.primary && (up.sendingPct != null || up.receivingPct != null))
        (s?.link as? ClientLink)?.let { cl ->
            val want = if (active) android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED else android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            if (want != p.linkPriority && !messenger.busy) { p.linkPriority = want; cl.setPriority(want) }
        }
        if (s == null || messenger.busy) p.linkPriority = -1   // a transfer sets its own priority; re-apply after
        // While the app is on screen, refresh the signal every tick (5 s) so bars/distance feel live.
        if (s != null && g.uiVisibility.appVisible && s.link is ClientLink && t - p.lastStatusSent <= STATUS_MS) {
            scope.launch { (s.link as ClientLink).readRssi()?.let { p.est.add(it); publishDistance(p) } }
        }
        if (s != null && t - p.lastStatusSent > (if (active) STATUS_MS else STATUS_IDLE_MS)) {
            p.lastStatusSent = t
            scope.launch {
                val rssi = (s.link as? ClientLink)?.readRssi()
                // Max Range: drop to the Coded PHY (long range) when the signal gets weak, back to fast PHY when close.
                if (rssi != null && g.settings.current.range == RangeMode.MAX) (s.link as? ClientLink)?.adaptPhy(rssi)
                if (rssi != null) { p.est.add(rssi); publishDistance(p) }
                if (p.primary) p.est.smoothedRssi?.let { extras.onSignal(it.roundToInt()) }
                messenger.sendStatus(s, rssi)
                messenger.retryTick(s)
            }
        } else if (s == null && t - p.lastSeen > 20_000 && p.est.smoothedRssi != null) {
            p.est.reset(); publishDistance(p)
        }
    }

    /** Bluetooth 5 long range (LE Coded PHY) available on this watch. */
    val longRangeSupported: Boolean get() = advertiser.longRangeSupported

    /** Saves the current primary signal as the "1 metre" reference. Returns the value or null. */
    fun calibrateDistance(): Int? {
        val p = primaryPeer() ?: return null
        val r = p.est.smoothedRssi?.roundToInt() ?: return null
        g.settings.update { it.copy(rssiAt1m = r) }
        publishDistance(p)
        return r
    }

    fun onRemoteStatus(s: SecureSession, battery: Int?, rssi: Int?) = scope.launch {
        val p = peerOf(s) ?: return@launch
        p.remoteBattery = battery
        val link = s.link
        if (rssi != null && !link.isClient) {
            p.est.add(rssi)
            if (g.settings.current.range == RangeMode.MAX && advertiser.longRangeSupported) (link as? com.wristradar.ble.ServerLink)?.adaptPhy(rssi)
        }
        publishDistance(p)
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
            if (g.trust.contacts.value.isEmpty()) stopIfIdle()
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
    fun onPaired(link: Link, contactId: String, name: String) {
        syncPeers()
        val p = peerLinks[contactId] ?: return
        if (!p.primary) g.trust.setContactName(contactId, name)
        p.tokenWindow = -1
        p.failCount = 0
        WristRadarService.start(ctx)
        if (link.isClient && !link.closed) scope.launch { handshakeAsClient(p, link) }
        else if (p.primary) setPhase(Phase.AUTHENTICATING, "Securing session…")
        applyRadio()
    }

    fun refresh() = scope.launch {
        if (!running) start().join()
        if (!running) return@launch
        val pp = primaryPeer() ?: run { setPhase(Phase.NOT_PAIRED, "Pair with a watch first"); return@launch }
        setPhase(Phase.REFRESHING, "Refreshing connection…")
        val s = pp.alive()
        if (s != null) {
            val ok = withTimeoutOrNull(5_000) { messenger.sendStatus(s, (s.link as? ClientLink)?.readRssi()) } == true
            if (ok) { delay(400); setPhase(Phase.CONNECTED, "Connection healthy"); return@launch }
            s.close()   // stale link; onLinkClosed will follow
            delay(300)
        }
        allPeers().forEach { it.failCount = 0; it.nextAttemptAt = 0 }
        boostUntil = now() + BOOST_MS
        scanner.stop(); advertiser.stop()
        delay(300)
        if (!server.isRunning) server.start()
        pp.refreshDeadline = now() + 30_000
        setPhase(Phase.SEARCHING, "Looking for your partner…")
        applyRadio()
        allPeers().forEach { maybeConnect(it) }
    }

    fun pushProfile() = scope.launch { allPeers().forEach { p -> p.alive()?.let { messenger.sendProfile(it) } } }

    /** Removes the primary contact (classic "Unpair"). */
    fun unpair() = scope.launch {
        val pp = primaryPeer() ?: return@launch
        removeContactNow(pp.id, notify = true)
    }

    /** Removes one contact: tells their watch, deletes the chat with them. */
    fun removeContact(id: String) = scope.launch { removeContactNow(id, notify = true) }

    private suspend fun removeContactNow(id: String, notify: Boolean) {
        val p = peer(id) ?: return
        if (notify) p.alive()?.let { s -> withTimeoutOrNull(3_000) { messenger.sendUnpair(s) } }
        val wasPrimary = p.primary
        p.session?.close(); p.session = null
        g.messages.clearConversation(messenger.convOf(id))
        groups.onContactRemoved(id)
        if (wasPrimary) g.profile.clearPartner()
        g.trust.removeContact(id)
        syncPeers()
        // The next contact became the primary: its chat moves to the primary slot ("").
        if (wasPrimary) g.trust.partner.value?.let { np ->
            g.messages.renameConversation("c:${np.id}", "")
            np.let { g.profile.setPartner(g.trust.contactName(it.id) ?: "Friend", null) }
        }
        g.notifier.cancelMessages()
        if (g.trust.contacts.value.isEmpty()) {
            _conn.value = ConnectionState(Phase.NOT_PAIRED, "Unpaired")
            WristRadarService.stop(ctx)
        } else if (wasPrimary) {
            _conn.value = ConnectionState(if (currentSession() != null) Phase.CONNECTED else Phase.SEARCHING, "")
        }
        publishPeers()
        applyRadio()
    }

    fun onRemoteUnpair(s: SecureSession) = scope.launch {
        val p = peerOf(s) ?: return@launch
        val name = p.name
        removeContactNow(p.id, notify = false)
        g.notifier.showInfo("Pairing removed", "$name unpaired. Pair again to chat.")
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

    private fun publishPeers() {
        val m = allPeers().associate { p ->
            p.id to PeerStatus(
                id = p.id, connected = p.alive() != null, connecting = p.connecting,
                rssi = p.est.smoothedRssi?.roundToInt(),
                distanceM = p.est.distanceMetres(g.settings.current.rssiAt1m)?.let { Math.round(it * 10) / 10.0 },
                lastSeen = p.lastSeen, battery = p.remoteBattery, typing = p.typing,
            )
        }
        if (m != _peers.value) _peers.value = m
    }

    private var lastDistancePublish = 0L

    /** Throttled + rounded so the UI isn't redrawn for every Bluetooth scan result. */
    private fun publishDistance(p: PeerLink) {
        if (!p.primary) return   // other contacts are refreshed with the 5 s tick (publishPeers)
        val t = now()
        val rssi = p.est.smoothedRssi?.roundToInt()
        val d = p.est.distanceMetres(g.settings.current.rssiAt1m)?.let { Math.round(it * 10) / 10.0 }
        val cur = _conn.value
        if (cur.rssi == rssi && cur.distanceM == d && cur.remoteBattery == p.remoteBattery) return
        val important = (cur.distanceM == null) != (d == null) || cur.remoteBattery != p.remoteBattery
        if (!important && t - lastDistancePublish < 250) return
        lastDistancePublish = t
        _conn.update { it.copy(rssi = rssi, distanceM = d, remoteBattery = p.remoteBattery) }
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
        /** No valid message from the contact for this long = dead link (they send status every <= 45 s). */
        private const val DEAD_MS = 130_000L
        /** A sent text unanswered AND nothing heard for this long = stuck link. */
        private const val STUCK_MS = 30_000L
        private const val SIMULTANEOUS_MS = 8_000L
        /** First versionCode that speaks the ML-KEM-1024 handshake. */
        const val KEM1024_SINCE = 17
        /** First versionCode with suite V4 (ML-DSA-87 + SHA-384) and PQ_ID. */
        const val SUITE_V4_SINCE = 18
        /** First versionCode with suite V5 (a new key for every message). */
        const val SUITE_V5_SINCE = 19
        /** First versionCode with contacts, groups and group calls. */
        const val GROUPS_SINCE = 23
        private const val ACTIVE_MS = 60_000L
        /** Max Range searches at full power for 5 minutes before easing off. */
        private const val SEARCH_HARD_MS = 300_000L
        private const val BOOST_MS = 60_000L
        /** Brand-new handshake (fresh X25519 + ML-KEM) every hour when idle: heals even a stolen session key. */
        private const val RENEW_MS = 60 * 60 * 1000L
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }
}
