package com.wristradar.core

import android.graphics.Bitmap
import com.wristradar.ble.Link
import com.wristradar.crypto.SecureChannel
import com.wristradar.protocol.F
import com.wristradar.protocol.frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Phase(val label: String) {
    NOT_PAIRED("NOT PAIRED"),
    STANDBY("STANDBY"),
    REFRESHING("REFRESHING"),
    SEARCHING("SEARCHING"),
    DEVICE_FOUND("DEVICE FOUND"),
    CONNECTING("CONNECTING"),
    AUTHENTICATING("AUTHENTICATING"),
    CONNECTED("CONNECTED"),
    FAILED("CONNECTION FAILED"),
    BLUETOOTH_OFF("BLUETOOTH OFF"),
    NO_PERMISSION("PERMISSION NEEDED"),
    UNSUPPORTED("NOT SUPPORTED");

    val busy: Boolean get() = this == REFRESHING || this == SEARCHING || this == DEVICE_FOUND ||
        this == CONNECTING || this == AUTHENTICATING
}

data class ConnectionState(
    val phase: Phase = Phase.STANDBY,
    val detail: String = "",
    val rssi: Int? = null,
    val distanceM: Double? = null,
    val remoteBattery: Int? = null,
)

data class NearbyUser(
    val discoveryId: Int,
    val name: String?,
    val avatar: Bitmap?,
    val rssi: Int,
    val distanceM: Double?,
    val lastSeen: Long,
)

sealed class PairingState {
    data object Idle : PairingState()
    data class Connecting(val name: String) : PairingState()
    data class WaitingForPeer(val name: String, val note: String) : PairingState()
    data class Incoming(val name: String) : PairingState()
    data class Verify(val name: String, val code: String, val myConfirmed: Boolean, val peerConfirmed: Boolean) : PairingState()
    data class Success(val name: String) : PairingState()
    data class Failed(val reason: String) : PairingState()

    val active: Boolean get() = this is Connecting || this is WaitingForPeer || this is Incoming || this is Verify
}

/** One authenticated, encrypted session with the trusted partner over a single [Link]. */
class SecureSession(val link: Link, private val channel: SecureChannel) {
    private val sendMutex = Mutex()
    /** Decrypted inbound app messages, processed strictly in order by one consumer. */
    val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    val startedAt = System.currentTimeMillis()

    /** Seal + transmit are atomic so record counters hit the wire in order. */
    suspend fun send(type: Byte, payload: ByteArray = ByteArray(0)): Boolean = sendMutex.withLock {
        if (link.closed) return@withLock false
        link.send(frame(F.SECURE, channel.seal(byteArrayOf(type) + payload)))
    }

    fun open(record: ByteArray): ByteArray? = channel.open(record)
    val lastError get() = channel.lastError

    fun close() {
        inbox.close()
        link.close()
        channel.destroy()
    }
}
