package com.wristradar.ble

import android.os.ParcelUuid
import com.wristradar.crypto.Crypto
import java.nio.ByteBuffer
import java.util.UUID

/**
 * GATT layout. The service UUID only marks "this is a WristRadar install"; it is not
 * a secret and never authenticates anything.
 */
object BleIds {
    val SERVICE: UUID = UUID.fromString("7b3f1a52-9c4e-4d0b-a6e1-5f2c8d9e0a17")
    val RX: UUID = UUID.fromString("7b3f1a53-9c4e-4d0b-a6e1-5f2c8d9e0a17") // client -> server (write)
    val TX: UUID = UUID.fromString("7b3f1a54-9c4e-4d0b-a6e1-5f2c8d9e0a17") // server -> client (notify)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val SERVICE_PARCEL = ParcelUuid(SERVICE)
    const val REQUESTED_MTU = 517
}

/**
 * 13-byte service data carried in the scan response (fits the 31-byte legacy limit with a
 * 128-bit UUID):  flags(1) | discoveryId(4) | token(8)
 *  - flags bit0: discoverable (user has the Nearby screen open)
 *  - discoveryId: random per discoverable session, lets the list track a device across
 *    Bluetooth MAC rotations. Zero when not discoverable.
 *  - token: HMAC(advertKey, 10-minute window) truncated, only meaningful to the paired
 *    partner. Random bytes when unpaired, so strangers cannot link sightings.
 */
data class AdvertPayload(val discoverable: Boolean, val discoveryId: Int, val token: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(if (discoverable) 1 else 0).putInt(discoveryId).put(token, 0, 8).array()

    companion object {
        const val SIZE = 13
        fun parse(b: ByteArray?): AdvertPayload? {
            if (b == null || b.size < SIZE) return null
            val bb = ByteBuffer.wrap(b)
            val flags = bb.get().toInt()
            val id = bb.int
            val token = ByteArray(8).also { bb.get(it) }
            return AdvertPayload(flags and 1 == 1, id, token)
        }

        const val WINDOW_MS = 10 * 60 * 1000L

        fun token(advertKey: ByteArray, window: Long): ByteArray =
            Crypto.hmac(advertKey, ByteBuffer.allocate(8).putLong(window).array()).copyOf(8)

        fun currentWindow(now: Long = System.currentTimeMillis()) = now / WINDOW_MS
    }
}
