package com.azhardhiaulhaq.macpad

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 decoder rendering straight to the SurfaceView surface. Input is queued from the session
 * reader thread; a dedicated thread drains output and releases each buffer for immediate render.
 */
internal class VideoDecoder(private val stats: Stats) {
    /** Receives KEYFRAME_REQUEST when the decoder needs a fresh keyframe. */
    @Volatile var session: Session? = null

    private val lock = Any()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var output: OutputLoop? = null
    private var width = 0
    private var height = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var needKeyframe = true
    /** A frame was dropped for lack of a surface or codec; the next codec needs a keyframe. */
    private var dropped = false
    /** A KEYFRAME_REQUEST has been sent since needKeyframe last became true. */
    private var keyframeRequested = false

    // Receive times by pts, to measure decode latency. Guarded by `ring`.
    private val ring = LongArray(RING * 2)
    private var ringPos = 0

    private val codecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    fun setSurface(s: Surface?) = synchronized(lock) {
        stopCodec()
        surface = s
        if (s != null && sps != null) {
            startCodec()
            dropped = false
            keyframeRequested = true
            session?.requestKeyframe()
        }
    }

    /** Handles VIDEO_CONFIG: [data] holds SPS and PPS in Annex B form. */
    fun configure(w: Int, h: Int, data: ByteArray, off: Int, len: Int) = synchronized(lock) {
        stopCodec()
        dropped = false
        width = w
        height = h
        sps = null
        pps = null
        splitAnnexB(data, off, off + len) { start, end ->
            val nal = ByteArray(4 + end - start)
            nal[3] = 1
            System.arraycopy(data, start, nal, 4, end - start)
            when (data[start].toInt() and 0x1f) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        if (sps == null || pps == null) {
            Log.w(TAG, "VIDEO_CONFIG without SPS/PPS")
            return@synchronized
        }
        if (surface != null) startCodec()
    }

    fun clear() = synchronized(lock) {
        stopCodec()
        sps = null
        pps = null
    }

    /** Handles VIDEO_FRAME. Called on the session reader thread. */
    fun queue(data: ByteArray, off: Int, len: Int, ptsMicros: Long, keyframe: Boolean, rxNanos: Long) {
        synchronized(lock) {
            if (output?.failed == true) restartAfterError()
            val c = codec
            if (c == null) {
                if (sps != null) dropped = true
                return
            }
            if (needKeyframe && !keyframe) {
                if (!keyframeRequested) {
                    keyframeRequested = true
                    session?.requestKeyframe()
                }
                return
            }
            try {
                val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index < 0) {
                    Log.w(TAG, "decoder input stalled, dropping frame")
                    needKeyframe = true
                    session?.requestKeyframe()
                    keyframeRequested = true
                    return
                }
                val buffer = c.getInputBuffer(index)!!
                if (len > buffer.capacity()) {
                    Log.w(TAG, "frame of $len bytes exceeds input buffer ${buffer.capacity()}")
                    c.queueInputBuffer(index, 0, 0, ptsMicros, 0)
                    needKeyframe = true
                    session?.requestKeyframe()
                    keyframeRequested = true
                    return
                }
                buffer.clear()
                buffer.put(data, off, len)
                synchronized(ring) {
                    ring[ringPos * 2] = ptsMicros
                    ring[ringPos * 2 + 1] = rxNanos
                    ringPos = (ringPos + 1) % RING
                }
                c.queueInputBuffer(index, 0, len, ptsMicros, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                needKeyframe = false
                keyframeRequested = false
            } catch (e: IllegalStateException) { // includes MediaCodec.CodecException
                Log.w(TAG, "decoder error on input: $e")
                restartAfterError()
            }
        }
    }

    private fun restartAfterError() {
        stopCodec()
        if (surface != null && sps != null) startCodec()
        session?.requestKeyframe()
        keyframeRequested = true
    }

    private fun startCodec() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
        val name = codecList.findDecoderForFormat(format)
        if (name == null) {
            Log.e(TAG, "no H.264 decoder for ${width}x$height")
            return
        }
        // Realtime priority can be refused when the hardware's realtime budget is taken; then
        // fall back to best-effort rather than showing nothing.
        for (priority in 0..1) {
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createByCodecName(name)
                val caps = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val lowLatency = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                format.setInteger(MediaFormat.KEY_PRIORITY, priority)
                c.configure(format, surface, null, 0)
                c.start()
                Log.i(TAG, "decoder $name ${width}x$height lowLatency=$lowLatency priority=$priority")
            } catch (e: Exception) {
                Log.e(TAG, "decoder start failed with priority $priority", e)
                c?.release()
                continue
            }
            codec = c
            needKeyframe = true
            keyframeRequested = false
            output = OutputLoop(c).also { it.start() }
            return
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        output?.let {
            it.running = false
            it.join()
        }
        output = null
        codec = null
        try {
            c.stop()
        } catch (_: IllegalStateException) {
        }
        c.release()
    }

    private fun receiveTime(pts: Long): Long {
        synchronized(ring) {
            for (i in 0 until RING) if (ring[i * 2] == pts) return ring[i * 2 + 1]
        }
        return 0L
    }

    private inner class OutputLoop(private val c: MediaCodec) : Thread("MacPad-decoder") {
        @Volatile var running = true
        @Volatile var failed = false

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val index = c.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                    if (index < 0) continue
                    c.releaseOutputBuffer(index, true)
                    if (info.size > 0) {
                        stats.frames.incrementAndGet()
                        val rx = receiveTime(info.presentationTimeUs)
                        if (rx != 0L) stats.addDecode(System.nanoTime() - rx)
                    }
                }
            } catch (e: IllegalStateException) {
                if (running) {
                    Log.w(TAG, "decoder error on output: $e")
                    failed = true
                    // The keyframe answering this request triggers the codec restart in queue().
                    session?.requestKeyframe()
                }
            }
        }
    }

    private companion object {
        const val RING = 32
        const val INPUT_TIMEOUT_US = 200_000L
        const val OUTPUT_TIMEOUT_US = 20_000L

        /** Calls [nal] with the bounds of each NAL unit between 3- or 4-byte start codes. */
        inline fun splitAnnexB(b: ByteArray, from: Int, to: Int, nal: (Int, Int) -> Unit) {
            var start = -1
            var i = from
            while (i + 2 < to) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                    if (start >= 0) {
                        var end = i
                        if (end > start && b[end - 1].toInt() == 0) end-- // 4-byte start code
                        if (end > start) nal(start, end)
                    }
                    i += 3
                    start = i
                } else {
                    i++
                }
            }
            if (start in 0 until to) nal(start, to)
        }
    }
}
