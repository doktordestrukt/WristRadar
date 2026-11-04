package com.wristradar.crypto

import java.nio.ByteBuffer

/**
 * AES-256-GCM record layer for one authenticated session.
 *
 * Record: epoch(1) | counter(8) | ciphertext+tag(16). Header is authenticated as AAD.
 * Nonce:  direction(1) | 000 | counter(8)  -> never reused under one key (counter is strictly
 *         increasing for the life of the session and keys are unique per session/direction).
 * Replay: receiver rejects any counter <= the last accepted one.
 * Rotation: the sender ratchets its key (HKDF, one-way) every REKEY_RECORDS records or
 *         REKEY_MS; old keys are discarded, so a later key compromise cannot read earlier traffic.
 */
class SecureChannel(keys: SessionKeys, private val isClient: Boolean) {

    private var sKey = keys.sendKey.copyOf()
    private var sEpoch = 0
    private var sCounter = 0L
    private var sRecordsInEpoch = 0
    private var sEpochStart = System.currentTimeMillis()

    private var rKey = keys.recvKey.copyOf()
    private var rEpoch = 0
    private var rLast = -1L

    private val sendDir: Byte = if (isClient) 0x43 else 0x53
    private val recvDir: Byte = if (isClient) 0x53 else 0x43

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        if (sRecordsInEpoch >= REKEY_RECORDS || System.currentTimeMillis() - sEpochStart > REKEY_MS) {
            val next = Crypto.hkdf(sKey, null, "wr rekey")
            Crypto.wipe(sKey)
            sKey = next
            sEpoch = (sEpoch + 1) and 0xff
            sRecordsInEpoch = 0
            sEpochStart = System.currentTimeMillis()
        }
        val ctr = sCounter++
        sRecordsInEpoch++
        val header = ByteBuffer.allocate(9).put(sEpoch.toByte()).putLong(ctr).array()
        val ct = Crypto.gcmSeal(sKey, nonce(sendDir, ctr), plain, header)
        return header + ct
    }

    /** Returns plaintext, or null if forged, tampered, replayed or out of epoch window. */
    @Synchronized
    fun open(record: ByteArray): ByteArray? {
        lastError = RecordError.AUTH
        if (record.size < 9 + 16) return null
        val bb = ByteBuffer.wrap(record)
        val epoch = bb.get().toInt() and 0xff
        val ctr = bb.long
        val header = record.copyOfRange(0, 9)
        val ct = record.copyOfRange(9, record.size)
        if (ctr <= rLast) {
            // Only call it a replay if it is an authentic record we already accepted.
            if (epoch == rEpoch && Crypto.gcmOpen(rKey, nonce(recvDir, ctr), ct, header) != null) lastError = RecordError.REPLAY
            return null
        }
        return when (epoch) {
            rEpoch -> Crypto.gcmOpen(rKey, nonce(recvDir, ctr), ct, header)?.also { rLast = ctr }
            (rEpoch + 1) and 0xff -> {
                val next = Crypto.hkdf(rKey, null, "wr rekey")
                Crypto.gcmOpen(next, nonce(recvDir, ctr), ct, header)?.also {
                    Crypto.wipe(rKey)
                    rKey = next; rEpoch = epoch; rLast = ctr
                }
            }
            else -> null
        }
    }

    @Synchronized
    fun destroy() { Crypto.wipe(sKey); Crypto.wipe(rKey) }

    private fun nonce(dir: Byte, ctr: Long): ByteArray =
        ByteBuffer.allocate(12).put(dir).put(0).put(0).put(0).putLong(ctr).array()

    /** Why the last [open] call failed (valid only after it returned null). */
    @Volatile var lastError: RecordError = RecordError.AUTH; private set

    companion object {
        const val REKEY_RECORDS = 200
        const val REKEY_MS = 10 * 60 * 1000L
    }
}

enum class RecordError { AUTH, REPLAY }
