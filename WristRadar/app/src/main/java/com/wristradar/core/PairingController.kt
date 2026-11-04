package com.wristradar.core

import android.bluetooth.BluetoothDevice
import com.wristradar.AppGraph
import com.wristradar.ble.Link
import com.wristradar.crypto.MlKem
import com.wristradar.crypto.PairingCrypto
import com.wristradar.security.ThreatType
import com.wristradar.protocol.F
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import com.wristradar.protocol.frame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pairing state machine (see PairingCrypto for the cryptographic exchange). All methods run on
 * the engine's single-threaded dispatcher.
 */
class PairingController(private val g: AppGraph, private val engine: RadarEngine) {

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var crypto: PairingCrypto? = null
    private var link: Link? = null
    private var targetDiscoveryId: Int? = null
    private var peerName: String = ""
    private var pendingCommit: ByteArray? = null
    private var myConfirmed = false
    private var peerConfirmed = false
    private var timeoutJob: Job? = null

    private val myName get() = g.profile.myName.value

    /** Outgoing: the local user pressed PAIR on a nearby watch. */
    fun start(discoveryId: Int, device: BluetoothDevice, name: String) {
        if (_state.value.active) return
        if (g.trust.partner.value != null) {
            _state.value = PairingState.Failed("Unpair your current partner first (Settings → Unpair)."); return
        }
        reset()
        targetDiscoveryId = discoveryId
        peerName = name
        _state.value = PairingState.Connecting(name)
        armTimeout()
        engine.scope.launch {
            val l = engine.openClientLink(device)
            if (l == null) { fail("Could not reach $name. Move closer and try again."); return@launch }
            if (targetDiscoveryId != discoveryId || link != null) { l.close(); return@launch } // superseded
            link = l
            val c = PairingCrypto(isInitiator = true, identity = g.identity)
            crypto = c
            val ok = l.send(frame(F.PAIR_COMMIT, Out().i(engine.myDiscoveryId).raw(c.myCommit).str(myName).build()))
            if (!ok) { fail("Connection lost."); return@launch }
            if (_state.value is PairingState.Connecting) {
                _state.value = PairingState.WaitingForPeer(name, "Waiting for $name to accept…")
            }
        }
    }

    fun accept() {
        val l = link ?: return
        val commit = pendingCommit ?: return
        if (_state.value !is PairingState.Incoming) return
        val c = PairingCrypto(isInitiator = false, identity = g.identity)
        c.onPeerCommit(commit)
        crypto = c
        _state.value = PairingState.WaitingForPeer(peerName, "Exchanging keys…")
        engine.scope.launch {
            if (!l.send(frame(F.PAIR_RESPONSE, Out().raw(c.ephPub).raw(c.myId).raw(c.nonce).raw(c.kemPublicKey).str(myName).build()))) {
                fail("Connection lost.")
            }
        }
    }

    fun decline() = abort("Pairing declined.", "declined")

    fun cancel() = abort(null, "cancelled")

    /** Users compared codes. */
    fun confirm(match: Boolean) {
        val s = _state.value as? PairingState.Verify ?: return
        val c = crypto ?: return
        val l = link ?: return
        if (!match) { g.blackbox.report(ThreatType.CODE_MISMATCH, "You reported that the verification codes differed", l); abort("Codes did not match. Pairing cancelled for your safety.", "codes did not match"); return }
        myConfirmed = true
        _state.value = s.copy(myConfirmed = true)
        engine.scope.launch {
            if (!l.send(frame(F.PAIR_CONFIRM, c.myConfirmation()))) fail("Connection lost.") else checkComplete()
        }
    }

    fun reportError(msg: String) {
        if (!_state.value.active) _state.value = PairingState.Failed(msg)
    }

    fun dismissResult() {
        if (!_state.value.active) _state.value = PairingState.Idle
    }

    /** Frames of type PAIR_* not consumed by a request waiter. */
    fun onFrame(l: Link, f: ByteArray) {
        try {
            when (f[0]) {
                F.PAIR_COMMIT -> onCommit(l, f)
                F.PAIR_RESPONSE -> onResponse(l, f)
                F.PAIR_REVEAL -> onReveal(l, f)
                F.PAIR_CONFIRM -> onConfirm(l, f)
                F.PAIR_ABORT -> if (l === link) {
                    val why = runCatching { In(f, 1).str(200) }.getOrDefault("")
                    if (why == "collision") {
                        // They keep the initiator role; their PAIR_COMMIT is on its way to us.
                        val target = targetDiscoveryId
                        crypto?.wipe(); crypto = null
                        link = null
                        l.close()
                        targetDiscoveryId = target
                        _state.value = PairingState.WaitingForPeer(peerName, "Syncing with $peerName…")
                    } else {
                        reset(closeLink = true)
                        _state.value = PairingState.Failed("$peerName cancelled ($why).")
                    }
                }
            }
        } catch (e: Exception) {
            if (l === link) fail("Invalid pairing data received.")
        }
    }

    private fun onCommit(l: Link, f: ByteArray) {
        val r = In(f, 1)
        val theirDiscovery = r.i()
        val commit = r.raw(32)
        val name = r.str(80).take(20)
        fun reject(why: String) = engine.scope.launch { l.send(frame(F.PAIR_ABORT, Out().str(why).build())); delay(300); l.close() }

        if (g.trust.partner.value != null) { reject("already paired"); return }
        if (!engine.discoveryActive) { reject("not on pairing screen"); return }

        val s = _state.value
        if (s.active) {
            val collision = targetDiscoveryId == theirDiscovery && crypto?.isInitiator != false
            if (!collision) { reject("busy"); return }
            // Both pressed PAIR on each other: lower discovery id keeps the initiator role.
            if (engine.myDiscoveryId < theirDiscovery) { reject("collision"); return }
            reset(closeLink = true)
            link = l; peerName = name; pendingCommit = commit; targetDiscoveryId = theirDiscovery
            _state.value = PairingState.Incoming(name)
            armTimeout()
            accept()                         // we already chose this watch - accept automatically
            return
        }
        reset()
        link = l; peerName = name; pendingCommit = commit; targetDiscoveryId = theirDiscovery
        _state.value = PairingState.Incoming(name)
        armTimeout()
    }

    private fun onResponse(l: Link, f: ByteArray) {
        if (l !== link) return
        val c = crypto ?: return
        if (!c.isInitiator) return
        val r = In(f, 1)
        val eph = r.raw(32); val id = r.raw(32); val n = r.raw(16)
        val kemPk = r.raw(MlKem.PUBLIC_KEY_SIZE)
        val name = r.str(80).take(20)
        if (name.isNotBlank()) peerName = name
        if (!c.onResponse(eph, id, n, kemPk)) { abort("Key exchange failed.", "key exchange failed"); return }
        engine.scope.launch {
            if (!l.send(frame(F.PAIR_REVEAL, Out().raw(c.ephPub).raw(c.myId).raw(c.nonce).raw(c.kemCiphertext!!).build()))) {
                fail("Connection lost."); return@launch
            }
            _state.value = PairingState.Verify(peerName, c.sasCode(), false, false)
        }
    }

    private fun onReveal(l: Link, f: ByteArray) {
        if (l !== link) return
        val c = crypto ?: return
        if (c.isInitiator) return
        val r = In(f, 1)
        if (!c.onReveal(r.raw(32), r.raw(32), r.raw(16), r.raw(MlKem.CIPHERTEXT_SIZE))) {
            g.blackbox.report(ThreatType.PAIRING_TAMPER, "Pairing reveal did not match its commitment", l)
            engine.sendBlackbox(l)
            abort("Verification failed - possible interference. Pairing stopped.", "commitment mismatch"); return
        }
        _state.value = PairingState.Verify(peerName, c.sasCode(), false, false)
    }

    private fun onConfirm(l: Link, f: ByteArray) {
        if (l !== link) return
        val c = crypto ?: return
        if (!c.ready) return
        if (!c.verifyPeerConfirmation(f.copyOfRange(1, f.size))) {
            g.blackbox.report(ThreatType.PAIRING_TAMPER, "Pairing confirmation signature was invalid", l)
            engine.sendBlackbox(l)
            abort("Partner confirmation invalid. Pairing stopped.", "bad confirmation"); return
        }
        peerConfirmed = true
        (_state.value as? PairingState.Verify)?.let { _state.value = it.copy(peerConfirmed = true) }
        checkComplete()
    }

    private fun checkComplete() {
        if (!myConfirmed || !peerConfirmed) return
        val c = crypto ?: return
        val l = link ?: return
        val peerId = c.peerId ?: return
        g.trust.save(peerId, c.derivePsk())
        g.profile.setPartner(peerName, null)
        c.wipe()
        timeoutJob?.cancel()
        _state.value = PairingState.Success(peerName)
        crypto = null; link = null; pendingCommit = null
        engine.onPaired(l)
    }

    fun onLinkClosed(l: Link) {
        if (l === link && _state.value.active) fail("Connection to $peerName lost.")
    }

    private fun abort(userMessage: String?, wireReason: String) {
        val l = link
        reset(closeLink = false)
        if (l != null) engine.scope.launch {
            l.send(frame(F.PAIR_ABORT, Out().str(wireReason).build()))
            delay(300)
            l.close()
        }
        _state.value = if (userMessage == null) PairingState.Idle else PairingState.Failed(userMessage)
    }

    private fun fail(msg: String) {
        reset(closeLink = true)
        _state.value = PairingState.Failed(msg)
    }

    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = engine.scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            if (_state.value.active) abort("Pairing timed out.", "timeout")
        }
    }

    private fun reset(closeLink: Boolean = false) {
        timeoutJob?.cancel()
        crypto?.wipe()
        crypto = null
        if (closeLink) link?.close()
        link = null
        pendingCommit = null
        targetDiscoveryId = null
        myConfirmed = false
        peerConfirmed = false
    }

    companion object { const val PAIRING_TIMEOUT_MS = 120_000L }
}
