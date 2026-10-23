package net.prototux.aare.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Hardware H.264 encoder fed by a Surface (a virtual display renders into it).
 *
 * Head units decode Baseline/Constrained Baseline H.264 in Annex-B form, the same thing the
 * official app sends. Output buffers are copied into pooled arrays with [headroom] free bytes in
 * front, so the session can prepend its message header without another copy.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    private val bitrate: Int = defaultBitrate(width, height, fps),
    private val headroom: Int = 10,
    private val sink: Sink,
) {
    interface Sink {
        /** SPS/PPS. Sent once before the first frame, and again whenever the encoder restarts. */
        fun onCodecConfig(data: ByteArray, offset: Int, length: Int)

        /** One access unit. [data] has [offset] bytes of headroom; give it back with [recycle]. */
        fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean)

        fun onError(e: Exception)
    }

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    lateinit var inputSurface: Surface
        private set

    private val pool = ArrayDeque<ByteArray>()

    fun start() {
        val t = HandlerThread("aare-video-enc", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Long GOP: the transport is reliable, and we ask for key frames when they're needed.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, levelFor(width, height, fps))
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Keep frames flowing on a static screen so the head unit's decoder never stalls.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 5)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            // SPS/PPS before every key frame, so a head unit that resets its decoder can resync.
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(Callback(), Handler(t.looper))
        // Encoders differ in which tuning keys they accept: drop them step by step until one works.
        val fallbacks = listOf(
            emptyList(),
            listOf(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES),
            listOf(MediaFormat.KEY_LATENCY, MediaFormat.KEY_PRIORITY, MediaFormat.KEY_MAX_B_FRAMES),
            listOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL, MediaFormat.KEY_BITRATE_MODE),
            listOf(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER),
        )
        var configured = false
        var lastError: Exception? = null
        for (drop in fallbacks) {
            drop.forEach { format.removeKey(it) }
            try {
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "encoder ${c.name} rejected $format: ${e.message}")
                runCatching { c.reset() }
            }
        }
        if (!configured) {
            c.release(); t.quitSafely()
            throw IllegalStateException("no usable H.264 encoder configuration for ${width}x$height@$fps", lastError)
        }
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        Log.i(TAG, "encoder ${c.name} ${width}x$height@$fps ${bitrate / 1000} kb/s")
    }

    /** Asks for an IDR frame now (after focus changes, or when the head unit asks). */
    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Pauses or resumes output (no frames while the head unit shows its own UI). */
    fun setSuspended(suspended: Boolean) {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0) }) }
        if (!suspended) requestKeyFrame()
    }

    fun recycle(buffer: ByteArray) {
        synchronized(pool) { if (pool.size < 8) pool.addLast(buffer) }
    }

    private fun obtain(size: Int): ByteArray {
        synchronized(pool) {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.size >= size) { it.remove(); return b }
            }
        }
        // round up so buffers get reused across frames of similar size
        return ByteArray(((size + 16383) / 16384) * 16384)
    }

    fun stop() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
        runCatching { inputSurface.release() }
        thread?.quitSafely()
        thread = null
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // Surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset).limit(info.offset + info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val cfg = ByteArray(info.size)
                        buf.get(cfg)
                        sink.onCodecConfig(cfg, 0, cfg.size)
                    } else {
                        val out = obtain(headroom + info.size)
                        buf.get(out, headroom, info.size)
                        sink.onFrame(out, headroom, info.size, info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // released while a callback was in flight
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
            sink.onError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "encoder output format $format")
        }
    }

    companion object {
        private const val TAG = "AareVideoEnc"

        fun defaultBitrate(w: Int, h: Int, fps: Int): Int {
            // ~0.12 bits per pixel per frame: crisp UI text without saturating USB 2 / Wi-Fi.
            val bpp = 0.12
            return (w * h * fps * bpp).toInt().coerceIn(2_000_000, 16_000_000)
        }

        private fun levelFor(w: Int, h: Int, fps: Int): Int {
            val mbps = ((w + 15) / 16) * ((h + 15) / 16) * fps
            return when {
                mbps <= 40_500 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
                mbps <= 108_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32
                mbps <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
                mbps <= 522_240 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            }
        }
    }
}
