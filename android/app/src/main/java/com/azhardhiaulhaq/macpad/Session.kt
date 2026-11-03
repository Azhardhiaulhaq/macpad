package com.azhardhiaulhaq.macpad

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.interfaces.ECPublicKey
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import kotlin.concurrent.withLock

internal const val TAG = "MacPad"

internal class Endpoint(val host: String, val port: Int)

/**
 * Where to connect. Wi-Fi targets list candidate [endpoints] in order (NSD address first, then
 * remembered ones); the first that accepts wins. [serverIdHex] is null when the Mac is not known
 * yet (an address the user typed), which makes the handshake ask for pairing.
 */
internal class Target(
    val usb: Boolean,
    val endpoints: List<Endpoint>,
    val serverIdHex: String?,
    val name: String?,
    /** The user typed this address; failures name the address instead of the Mac. */
    val typed: Boolean = false,
) {
    companion object {
        const val USB_PORT = 47800
        const val WIFI_PORT = 47801

        fun usb() = Target(true, listOf(Endpoint("127.0.0.1", USB_PORT)), null, null)
    }
}

/** Counters for the periodic `stats` log line. Written from the reader and decoder threads. */
internal class Stats {
    val frames = AtomicInteger()
    val bytes = AtomicLong()
    private val rttSum = AtomicLong()
    private val rttCount = AtomicInteger()
    private val decodeSum = AtomicLong()
    private val decodeCount = AtomicInteger()
    @Volatile private var since = System.nanoTime()

    fun addRtt(nanos: Long) {
        rttSum.addAndGet(nanos)
        rttCount.incrementAndGet()
    }

    fun addDecode(nanos: Long) {
        decodeSum.addAndGet(nanos)
        decodeCount.incrementAndGet()
    }

    fun reset() {
        frames.set(0); bytes.set(0); rttSum.set(0); rttCount.set(0); decodeSum.set(0); decodeCount.set(0)
        since = System.nanoTime()
    }

    fun log() {
        val now = System.nanoTime()
        val seconds = (now - since) / 1e9
        since = now
        val frameCount = frames.getAndSet(0)
        val byteCount = bytes.getAndSet(0)
        val rtts = rttCount.getAndSet(0)
        val rttTotal = rttSum.getAndSet(0)
        val decodes = decodeCount.getAndSet(0)
        val decodeTotal = decodeSum.getAndSet(0)
        Log.i(
            TAG,
            String.format(
                Locale.US, "stats fps=%.1f mbps=%.2f rtt=%.1f decode=%.1f",
                frameCount / seconds,
                byteCount * 8 / seconds / 1e6,
                if (rtts == 0) 0.0 else rttTotal / 1e6 / rtts,
                if (decodes == 0) 0.0 else decodeTotal / 1e6 / decodes,
            ),
        )
    }
}

/**
 * One TCP connection to the Mac (docs/PROTOCOL.md). The reader thread connects, runs the Wi-Fi
 * handshake, then reads frames; the writer thread drains a double-buffered outbox so callers on
 * the main thread never block or allocate per event. Listener callbacks run on the main thread.
 */
internal class Session(
    val target: Target,
    private val prefs: Prefs,
    private val deviceName: String,
    private var panelWidth: Int,
    private var panelHeight: Int,
    private val densityDpi: Int,
    private val connectTimeoutMs: Int,
    /** A background USB probe: failures to connect are not reported to the user. */
    val quiet: Boolean,
    private val listener: Listener,
    private val decoder: VideoDecoder,
    private val stats: Stats,
) {
    interface Listener {
        fun onPairingCode(session: Session, code: Int)
        fun onServerInfo(session: Session, macName: String)
        fun onVideoSize(session: Session, width: Int, height: Int)
        fun onNotice(session: Session, code: Int, message: String)
        fun onEnded(session: Session, end: End)
    }

    enum class End { UNREACHABLE, REJECTED, KEYS_DISAGREE, LOST, PROTOCOL }

    private class EndException(val end: End) : Exception()
    private class ProtocolError(message: String) : Exception(message)

    private enum class Phase { CONNECT, HANDSHAKE, PAIRING, AWAIT_FIRST, STREAMING }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var ticker: Thread? = null

    // Outbox: producers append plain frames to `pending`; the writer swaps buffers and sends.
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private var pending = ByteArray(4096)
    private var pendingLen = 0
    private var writing = ByteArray(4096)
    private var helloQueued = false

    // Reader state (reader thread only).
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var readBuf = ByteArray(1 shl 16)
    private var plainBuf = ByteArray(1 shl 16)
    private var frame = readBuf
    private var frameLen = 0
    private var frameRxNanos = 0L
    private var phase = Phase.CONNECT
    private var pairedMode = false
    private var serverIdHex: String? = null
    private var connectedHost: String? = null
    @Volatile private var sendGcm: Gcm? = null
    private var recvGcm: Gcm? = null

    fun start() {
        Thread(::run, "MacPad-reader").start()
    }

    /** Closes the connection without reporting [Listener.onEnded]. */
    fun close() {
        closed = true
        closeSocket()
        lock.withLock { ready.signalAll() }
        ticker?.interrupt()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    // ---- Sending (any thread) ----

    fun resize(width: Int, height: Int) {
        lock.withLock {
            panelWidth = width
            panelHeight = height
            if (!helloQueued || closed) return
            val p = begin(0x02, 4)
            put16(pending, p, width)
            put16(pending, p + 2, height)
            ready.signal()
        }
    }

    fun pointer(action: Int, tool: Int, buttons: Int, x: Int, y: Int, pressure: Int, tiltX: Int, tiltY: Int) {
        lock.withLock {
            if (!helloQueued || closed) return
            val p = begin(0x03, 13)
            val b = pending
            b[p] = action.toByte()
            b[p + 1] = tool.toByte()
            b[p + 2] = buttons.toByte()
            put16(b, p + 3, x)
            put16(b, p + 5, y)
            put16(b, p + 7, pressure)
            put16(b, p + 9, tiltX)
            put16(b, p + 11, tiltY)
            ready.signal()
        }
    }

    fun scroll(phase: Int, x: Int, y: Int, dx: Int, dy: Int) {
        lock.withLock {
            if (!helloQueued || closed) return
            val p = begin(0x04, 9)
            val b = pending
            b[p] = phase.toByte()
            put16(b, p + 1, x)
            put16(b, p + 3, y)
            put16(b, p + 5, dx)
            put16(b, p + 7, dy)
            ready.signal()
        }
    }

    fun requestKeyframe() {
        lock.withLock {
            if (!helloQueued || closed) return
            begin(0x05, 0)
            ready.signal()
        }
    }

    private fun ping() {
        lock.withLock {
            if (closed) return
            put64(pending, begin(0x06, 8), System.nanoTime())
            ready.signal()
        }
    }

    private fun queueHello() = lock.withLock {
        val name = utf8(deviceName)
        val p = begin(0x01, 2 + 2 + 2 + 2 + 16 + 2 + name.size)
        val b = pending
        put16(b, p, 1)
        put16(b, p + 2, panelWidth)
        put16(b, p + 4, panelHeight)
        put16(b, p + 6, densityDpi)
        System.arraycopy(prefs.clientId, 0, b, p + 8, 16)
        put16(b, p + 24, name.size)
        System.arraycopy(name, 0, b, p + 26, name.size)
        helloQueued = true
        ready.signal()
    }

    /** Reserves a plain frame in the outbox and returns the payload offset. Caller holds [lock]. */
    private fun begin(type: Int, payloadLen: Int): Int {
        val start = pendingLen
        val end = start + 5 + payloadLen
        if (end > pending.size) pending = pending.copyOf(maxOf(end, pending.size * 2))
        put32(pending, start, 1 + payloadLen)
        pending[start + 4] = type.toByte()
        pendingLen = end
        return start + 5
    }

    private fun writerLoop() {
        var enc = ByteArray(4096)
        try {
            while (true) {
                var n: Int
                lock.withLock {
                    while (pendingLen == 0 && !closed) ready.await()
                    if (closed) return
                    val t = writing
                    writing = pending
                    pending = t
                    n = pendingLen
                    pendingLen = 0
                }
                val gcm = sendGcm
                if (gcm == null) {
                    output.write(writing, 0, n)
                } else {
                    var p = 0
                    while (p < n) {
                        val len = get32(writing, p)
                        if (enc.size < len + Gcm.TAG_SIZE + 4) enc = ByteArray(len + Gcm.TAG_SIZE + 4 + 4096)
                        val c = gcm.process(writing, p + 4, len, enc, 4)
                        put32(enc, 0, c)
                        output.write(enc, 0, 4 + c)
                        p += 4 + len
                    }
                }
                output.flush()
            }
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "write failed: $e")
            closeSocket()
        }
    }

    // ---- Reader thread ----

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var end = End.PROTOCOL
        var wasClosed = false
        try {
            var s = connect() ?: return
            openStreams(s)
            if (!target.usb) {
                val pairedId = handshake(s, target.serverIdHex)
                if (pairedId != null) {
                    // A typed address turned out to be a Mac this tablet is paired with: reconnect
                    // claiming the key instead of starting a new pairing.
                    closeSocket()
                    s = connect() ?: return
                    openStreams(s)
                    handshake(s, pairedId)
                }
            }
            queueHello()
            phase = Phase.AWAIT_FIRST
            Thread(::writerLoop, "MacPad-writer").start()
            s.soTimeout = READ_TIMEOUT_MS
            startTicker()
            while (true) {
                val type = readFrame()
                phase = Phase.STREAMING
                dispatch(type)
            }
        } catch (e: EndException) {
            end = e.end
        } catch (e: GeneralSecurityException) {
            // A frame that does not decrypt right after HELLO in paired mode means the keys disagree.
            end = if (phase == Phase.AWAIT_FIRST && pairedMode) End.KEYS_DISAGREE else End.PROTOCOL
            if (!closed) Log.w(TAG, "crypto failure in $phase: $e")
        } catch (e: IOException) {
            end = when (phase) {
                Phase.CONNECT, Phase.HANDSHAKE, Phase.PAIRING -> End.UNREACHABLE
                // The Mac closes right after an undecryptable HELLO; a timeout is not that signal.
                Phase.AWAIT_FIRST ->
                    if (pairedMode && e !is SocketTimeoutException) End.KEYS_DISAGREE else End.UNREACHABLE
                Phase.STREAMING -> End.LOST
            }
            if (!closed && !quiet) Log.i(TAG, "connection ended in $phase: $e")
        } catch (e: ProtocolError) {
            end = End.PROTOCOL
            Log.w(TAG, "protocol error: ${e.message}")
        } catch (e: RuntimeException) {
            end = End.PROTOCOL
            Log.w(TAG, "unexpected failure", e)
        } finally {
            // Mark closed before waking the writer, or it re-parks on `!closed` forever.
            wasClosed = closed
            closed = true
            closeSocket()
            lock.withLock { ready.signalAll() }
            ticker?.interrupt()
        }
        if (wasClosed) return
        if (end == End.KEYS_DISAGREE) serverIdHex?.let { prefs.deletePairingKey(it) }
        main.post { listener.onEnded(this, end) }
    }

    /** Tries each endpoint in order; the last failure propagates. Null when closed meanwhile. */
    private fun connect(): Socket? {
        var failure: IOException? = null
        for (endpoint in target.endpoints) {
            if (closed) return null
            val s = Socket()
            socket = s
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByName(endpoint.host), endpoint.port), connectTimeoutMs)
                connectedHost = endpoint.host
                return s
            } catch (e: IOException) {
                closeSocket()
                failure = e
            }
        }
        throw failure ?: IOException("no endpoints")
    }

    private fun openStreams(s: Socket) {
        input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        output = BufferedOutputStream(s.getOutputStream(), 1 shl 16)
    }

    /**
     * Runs the Wi-Fi key exchange. Returns the Mac's server id when the Mac asked to pair only
     * because [txtId] was unknown while a key for that Mac is already stored; the caller retries.
     */
    private fun handshake(s: Socket, txtId: String?): String? {
        phase = Phase.HANDSHAKE
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        val keyPair = Crypto.newKeyPair()
        val clientPub = Crypto.encodePoint(keyPair.public)
        val clientId = prefs.clientId
        val hasKey = txtId != null && prefs.hasPairingKey(txtId)
        val name = utf8(deviceName)

        val hello = ByteArray(2 + 16 + Crypto.POINT_SIZE + 1 + 2 + name.size)
        put16(hello, 0, 1)
        System.arraycopy(clientId, 0, hello, 2, 16)
        System.arraycopy(clientPub, 0, hello, 18, Crypto.POINT_SIZE)
        hello[83] = if (hasKey) 1 else 0
        put16(hello, 84, name.size)
        System.arraycopy(name, 0, hello, 86, name.size)
        writePlain(0x10, hello)

        if (readFrame() != 0x90 || frameLen < 1 + 16 + Crypto.POINT_SIZE + 1) throw ProtocolError("expected WIFI_HELLO_REPLY")
        val serverId = frame.copyOfRange(1, 17)
        val serverPub = frame.copyOfRange(17, 17 + Crypto.POINT_SIZE)
        val mode = frame[82].toInt()
        val serverHex = serverId.toHex()
        serverIdHex = serverHex
        val transcript = clientId + clientPub + serverId + serverPub
        val params = (keyPair.public as ECPublicKey).params
        val shared = Crypto.ecdh(keyPair.private, Crypto.decodePoint(serverPub, 0, params))

        val pairingKey: ByteArray = when (mode) {
            0 -> {
                pairedMode = true
                prefs.pairingKey(serverHex) ?: throw ProtocolError("paired mode without a stored key")
            }
            1 -> {
                if (!hasKey && prefs.hasPairingKey(serverHex)) return serverHex
                phase = Phase.PAIRING
                val code = Crypto.sas(shared, transcript)
                main.post { listener.onPairingCode(this, code) }
                s.soTimeout = 0 // the user decides on the Mac; Cancel closes the socket
                when (readFrame()) {
                    // Keys change only once the Mac accepts, so a cancelled re-pair keeps the old pairing.
                    0x91 -> Crypto.pairingKey(shared, transcript).also {
                        prefs.storePairingKey(serverHex, it)
                        if (txtId != null && txtId != serverHex) prefs.deletePairingKey(txtId)
                    }
                    0x92 -> throw EndException(End.REJECTED)
                    else -> throw ProtocolError("expected PAIR_ACCEPTED or PAIR_REJECTED")
                }
            }
            else -> throw ProtocolError("unknown pairing mode $mode")
        }
        // Remember where this Mac answered, so it is listed even when Bonjour cannot reach the tablet.
        connectedHost?.let { prefs.addMacAddress(serverHex, target.name ?: it, it) }
        sendGcm = Gcm(Crypto.sessionKey(shared, pairingKey, "c2s", transcript), 1, Cipher.ENCRYPT_MODE)
        recvGcm = Gcm(Crypto.sessionKey(shared, pairingKey, "s2c", transcript), 2, Cipher.DECRYPT_MODE)
        return null
    }

    private fun writePlain(type: Int, payload: ByteArray) {
        val header = ByteArray(5)
        put32(header, 0, 1 + payload.size)
        header[4] = type.toByte()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    /** Reads one frame into [frame] (type at index 0) and returns its type. */
    private fun readFrame(): Int {
        val len = input.readInt()
        val gcm = recvGcm
        val overhead = if (gcm == null) 0 else Gcm.TAG_SIZE
        if (len < 1 + overhead || len > MAX_FRAME + overhead) throw ProtocolError("bad frame length $len")
        if (readBuf.size < len) readBuf = ByteArray(len + (len shr 2))
        input.readFully(readBuf, 0, len)
        frameRxNanos = System.nanoTime()
        stats.bytes.addAndGet(4L + len)
        if (gcm == null) {
            frame = readBuf
            frameLen = len
        } else {
            if (plainBuf.size < len) plainBuf = ByteArray(len + (len shr 2))
            frameLen = gcm.process(readBuf, 0, len, plainBuf, 0)
            if (frameLen < 1) throw ProtocolError("empty frame")
            frame = plainBuf
        }
        return frame[0].toInt() and 0xff
    }

    private fun dispatch(type: Int) {
        val b = frame
        when (type) {
            0x80 -> {
                val name = readString(1)
                rememberMac(name, 1 + 2 + get16(b, 1))
                main.post { listener.onServerInfo(this, name) }
            }
            0x81 -> {
                need(5)
                val w = get16(b, 1)
                val h = get16(b, 3)
                decoder.configure(w, h, b, 5, frameLen - 5)
                main.post { listener.onVideoSize(this, w, h) }
            }
            0x82 -> {
                need(10)
                decoder.queue(b, 10, frameLen - 10, get64(b, 1), (b[9].toInt() and 1) != 0, frameRxNanos)
            }
            0x83 -> {
                need(9)
                val rtt = System.nanoTime() - get64(b, 1)
                stats.addRtt(rtt)
                Log.d(TAG, String.format(Locale.US, "rtt=%.1f", rtt / 1e6))
            }
            0x84 -> {
                need(2)
                val code = b[1].toInt() and 0xff
                val message = readString(2)
                main.post { listener.onNotice(this, code, message) }
            }
            // Unknown types are ignored so newer Macs can add messages.
        }
    }

    /** SERVER_INFO after the name: 16B serverId, u8 count, count x string address. */
    private fun rememberMac(name: String, offset: Int) {
        need(offset + 17)
        val serverHex = frame.copyOfRange(offset, offset + 16).toHex()
        val count = frame[offset + 16].toInt() and 0xff
        var p = offset + 17
        val addresses = ArrayList<String>(count)
        repeat(count) {
            addresses.add(readString(p))
            p += 2 + get16(frame, p)
        }
        prefs.rememberMac(serverHex, name, addresses)
    }

    private fun need(len: Int) {
        if (frameLen < len) throw ProtocolError("frame too short")
    }

    private fun readString(offset: Int): String {
        need(offset + 2)
        val len = get16(frame, offset)
        need(offset + 2 + len)
        return String(frame, offset + 2, len, Charsets.UTF_8)
    }

    private fun startTicker() {
        val t = Thread({
            var tick = 0
            try {
                while (!closed) {
                    Thread.sleep(1000)
                    tick++
                    if (tick % 2 == 0) ping()
                    if (tick % 5 == 0) stats.log()
                }
            } catch (_: InterruptedException) {
            }
        }, "MacPad-ticker")
        t.isDaemon = true
        ticker = t
        t.start()
        if (closed) t.interrupt()
    }

    private companion object {
        const val MAX_FRAME = 16 shl 20
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        /** PONGs arrive every 2 s, so silence this long means the link is gone. */
        const val READ_TIMEOUT_MS = 6_000

        fun utf8(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            return if (b.size <= 0xffff) b else b.copyOf(0xffff)
        }

        fun put16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 8).toByte()
            b[o + 1] = v.toByte()
        }

        fun put32(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte()
            b[o + 1] = (v ushr 16).toByte()
            b[o + 2] = (v ushr 8).toByte()
            b[o + 3] = v.toByte()
        }

        fun put64(b: ByteArray, o: Int, v: Long) {
            for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte()
        }

        fun get16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

        fun get32(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

        fun get64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xff)
            return v
        }
    }
}
