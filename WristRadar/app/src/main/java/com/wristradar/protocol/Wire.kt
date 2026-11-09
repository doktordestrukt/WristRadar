package com.wristradar.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/** Link-level frame types. Only PROFILE_*, PAIR_* and HS_* travel in clear; everything else is SECURE. */
object F {
    const val PROFILE_REQ: Byte = 0x01
    const val PROFILE_RESP: Byte = 0x02
    const val PAIR_COMMIT: Byte = 0x10
    const val PAIR_RESPONSE: Byte = 0x11
    const val PAIR_REVEAL: Byte = 0x12
    const val PAIR_CONFIRM: Byte = 0x13
    const val PAIR_ABORT: Byte = 0x14
    const val HS_HELLO: Byte = 0x20
    const val HS_SERVER: Byte = 0x21
    const val HS_FINISH: Byte = 0x22
    const val HS_REJECT: Byte = 0x23
    /** Sent to a device that failed authentication; its WristRadar shows the BLACKBOX screen. */
    const val BLACKBOX: Byte = 0x24
    const val SECURE: Byte = 0x30
}

/** Message types inside the encrypted channel. */
object A {
    const val TEXT: Byte = 0x40
    const val ACK: Byte = 0x41
    const val PROFILE: Byte = 0x42
    const val STATUS: Byte = 0x43
    const val IMG_START: Byte = 0x44
    const val IMG_CHUNK: Byte = 0x45
    const val IMG_STATUS: Byte = 0x46
    const val UNPAIR: Byte = 0x47
    const val IMG_FAIL: Byte = 0x49
    const val TYPING: Byte = 0x4A
    /** Read receipt: count int + message UUIDs the reader has seen. */
    const val SEEN: Byte = 0x4B
    /** Reaction: target uuid + emoji string ("" removes it). */
    const val REACT: Byte = 0x4C
    /** Edit own message: target uuid + new text. */
    const val EDIT: Byte = 0x4D
    /** Delete own message for both: target uuid. */
    const val DELETE: Byte = 0x4E
    /** Pin/unpin for both: target uuid + byte (1 = pinned). */
    const val PIN: Byte = 0x4F
    const val CALL_INVITE: Byte = 0x50
    const val CALL_ACCEPT: Byte = 0x51
    const val CALL_DECLINE: Byte = 0x52
    const val CALL_END: Byte = 0x53
    const val CALL_AUDIO: Byte = 0x54
    const val SECURITY_NOTICE: Byte = 0x55
    /** App version: code int + label + platform byte (0 watch, 1 PC). Sent on every connect. */
    const val VERSION: Byte = 0x56
    /** Bluetooth app update: offer / request(offset) / chunk / ack. See Updater. */
    const val UPD_OFFER: Byte = 0x57
    const val UPD_REQ: Byte = 0x58
    const val UPD_CHUNK: Byte = 0x59
    const val UPD_ACK: Byte = 0x5A
    /** Buzz the partner's watch. */
    const val NUDGE: Byte = 0x5B
    /** Full-screen emoji on the partner's watch: emoji string. */
    const val BLAST: Byte = 0x5C
    /** Games: game byte, op byte, id int, value int. */
    const val GAME: Byte = 0x5D
    /** Tap code: count int + vibration durations (ms, on/off alternating). */
    const val TAPS: Byte = 0x5E
    /** Secret text: like TEXT plus a flags byte (1 = vanish after reading, 2 = hold to reveal). */
    const val SECRET: Byte = 0x5F
    /** SOS: byte 1 = SOS, 0 = "I saw it, coming". Vibration only, no sound. */
    const val SOS: Byte = 0x60
    /** Shared to-do list: op byte (0 add, 1 done, 2 delete) + uuid + data. */
    const val TODO: Byte = 0x61
    /** Mood / status text. */
    const val MOOD: Byte = 0x62
    /** A vanishing secret was opened: uuid (sender deletes its copy too). */
    const val OPENED: Byte = 0x63
    /** Message effect (MK14+): effect id byte. Plays full screen on the partner's watch. */
    const val EFFECT: Byte = 0x64
}

class Out {
    private val bos = ByteArrayOutputStream()
    private val d = DataOutputStream(bos)
    fun b(v: Byte) = apply { d.writeByte(v.toInt()) }
    fun i(v: Int) = apply { d.writeInt(v) }
    fun l(v: Long) = apply { d.writeLong(v) }
    fun raw(v: ByteArray) = apply { d.write(v) }
    fun bytes(v: ByteArray?) = apply { d.writeInt(v?.size ?: -1); if (v != null) d.write(v) }
    fun str(v: String) = bytes(v.toByteArray(Charsets.UTF_8))
    fun uuid(v: UUID) = apply { d.writeLong(v.mostSignificantBits); d.writeLong(v.leastSignificantBits) }
    fun build(): ByteArray { d.flush(); return bos.toByteArray() }
}

class In(data: ByteArray, offset: Int = 0) {
    private val d = DataInputStream(data.inputStream(offset, data.size - offset))
    /** Optional trailing fields added in later versions are read only if present. */
    fun hasMore(): Boolean = d.available() > 0
    fun b(): Byte = d.readByte()
    fun i(): Int = d.readInt()
    fun l(): Long = d.readLong()
    fun raw(n: Int): ByteArray = ByteArray(n).also { d.readFully(it) }
    fun bytes(max: Int = 70_000): ByteArray? {
        val n = d.readInt()
        if (n < 0) return null
        require(n <= max) { "field too large" }
        return raw(n)
    }
    fun str(max: Int = 4096): String = String(bytes(max) ?: ByteArray(0), Charsets.UTF_8)
    fun uuid(): UUID = UUID(d.readLong(), d.readLong())
}

fun frame(type: Byte, payload: ByteArray = ByteArray(0)): ByteArray = byteArrayOf(type) + payload

fun uuidBytes(u: UUID): ByteArray =
    ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
