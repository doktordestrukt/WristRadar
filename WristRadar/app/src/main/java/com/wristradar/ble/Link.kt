package com.wristradar.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bidirectional framed pipe over one GATT connection.
 * Client role writes to RX; server role notifies on TX. Frames larger than the ATT payload are
 * split into fragments: header byte (0x80 = first, 0x40 = last) + data. GATT writes-with-response
 * and notification-sent callbacks give us per-fragment flow control, and the ATT bearer is ordered.
 */
abstract class Link(val isClient: Boolean, val address: String) {
    val id: Int = counter.incrementAndGet()
    @Volatile var closed = false; private set
    @Volatile var onFrame: (Link, ByteArray) -> Unit = { _, _ -> }
    @Volatile var onClosed: (Link) -> Unit = {}
    /** Bluetooth name of the remote device if the system knows it (often null for watches). */
    open val deviceName: String? get() = null

    protected val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Boolean>? = null
    private val reassembler = Reassembler()

    protected abstract val maxFragment: Int
    /** Starts transmitting one fragment; completion is signalled via [fragmentDone]. */
    protected abstract fun writeFragment(bytes: ByteArray): Boolean
    abstract fun close()

    suspend fun send(frame: ByteArray): Boolean = opMutex.withLock {
        // ATT attribute values are capped at 512 bytes (Android throws above that), whatever the MTU.
        val max = (maxFragment - 1).coerceIn(19, MAX_ATTR - 1)
        var off = 0
        var first = true
        do {
            if (closed) return@withLock false
            val n = minOf(max, frame.size - off)
            val last = off + n >= frame.size
            var hdr = 0
            if (first) hdr = hdr or 0x80
            if (last) hdr = hdr or 0x40
            val frag = ByteArray(n + 1)
            frag[0] = hdr.toByte()
            System.arraycopy(frame, off, frag, 1, n)
            val done = CompletableDeferred<Boolean>()
            pending = done
            var started = safeWrite(frag)
            var attempts = 0
            while (!started && attempts++ < 5 && !closed) {      // stack busy -> brief backoff
                kotlinx.coroutines.delay(30L * attempts)
                started = safeWrite(frag)
            }
            if (!started) { close(); return@withLock false }
            val ok = withTimeoutOrNull(FRAGMENT_TIMEOUT_MS) { done.await() } ?: false
            if (!ok) { close(); return@withLock false }
            off += n
            first = false
        } while (off < frame.size)
        true
    }

    private fun safeWrite(b: ByteArray): Boolean = try { writeFragment(b) } catch (e: Exception) { false }

    fun fragmentDone(ok: Boolean) { pending?.complete(ok) }

    fun onFragment(bytes: ByteArray) {
        val frame = reassembler.accept(bytes) ?: return
        onFrame(this, frame)
    }

    protected fun markClosed() {
        if (closed) return
        closed = true
        pending?.complete(false)
        onClosed(this)
    }

    companion object {
        private val counter = AtomicInteger()
        const val FRAGMENT_TIMEOUT_MS = 5_000L
        const val MAX_FRAME = 64 * 1024
        const val MAX_ATTR = 512
    }

    private class Reassembler {
        private var buf: ByteArrayOutputStream? = null
        fun accept(f: ByteArray): ByteArray? {
            if (f.isEmpty()) return null
            val h = f[0].toInt()
            if (h and 0x80 != 0) buf = ByteArrayOutputStream()
            val b = buf ?: return null                    // middle fragment without a start: drop
            b.write(f, 1, f.size - 1)
            if (b.size() > MAX_FRAME) { buf = null; return null }
            if (h and 0x40 != 0) { buf = null; return b.toByteArray() }
            return null
        }
    }
}
