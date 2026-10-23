package net.prototux.aare.headunit

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.DataInputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Decodes the projection with an ffmpeg child process (H.264 Annex-B in, raw BGR frames out).
 * ffmpeg is everywhere on Linux desktops and decodes faster than anything we'd write.
 */
class VideoDecoder(private val width: Int, private val height: Int, private val onFrame: (BufferedImage) -> Unit) {
    private val process: Process = ProcessBuilder(
        listOf("ffmpeg", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay",
            // Probe little: the SPS/PPS and the first key frame (bigger than this) say everything.
            "-probesize", "4096", "-analyzeduration", "0", "-f", "h264", "-i", "pipe:0") +
            // One output frame per input frame, as soon as it is decoded (no frame-rate conversion).
            (if (newFfmpeg) listOf("-fps_mode", "passthrough") else listOf("-vsync", "passthrough")) +
            // Always exactly the size read below, whatever the phone actually encoded.
            listOf("-vf", "scale=$width:$height", "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1"),
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input: OutputStream = process.outputStream
    // The writer must never block the protocol thread: drop if ffmpeg falls badly behind.
    private val queue = ArrayBlockingQueue<ByteArray>(120)
    @Volatile var frames = 0
        private set

    init {
        Thread({
            try {
                while (true) { input.write(queue.take()); input.flush() }
            } catch (_: Exception) {
            }
        }, "ffmpeg-in").apply { isDaemon = true; start() }
        Thread({
            val data = DataInputStream(process.inputStream.buffered(width * height * 3))
            try {
                while (true) {
                    val img = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                    data.readFully((img.raster.dataBuffer as DataBufferByte).data)
                    frames++
                    onFrame(img)
                }
            } catch (_: Exception) {
            }
        }, "ffmpeg-out").apply { isDaemon = true; start() }
    }

    /** False once ffmpeg exited (bad stream, killed): the caller starts a new decoder. */
    val alive: Boolean get() = process.isAlive

    /**
     * Queues one access unit (a frame, or the SPS/PPS). An access unit delimiter follows it: ffmpeg's
     * H.264 parser only outputs a frame once it sees the start of the next one, so without it the
     * screen would always be a frame late, and stuck on the one before last when the picture stops.
     */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        val unit = ByteArray(length + AUD.size)
        System.arraycopy(data, offset, unit, 0, length)
        AUD.copyInto(unit, length)
        if (!queue.offer(unit)) dropped++
    }

    /** Frames dropped because ffmpeg fell behind (the picture is damaged until the next key frame). */
    @Volatile var dropped = 0
        private set

    fun close() {
        runCatching { input.close() }
        process.destroy()
    }

    companion object {
        /** H.264 access unit delimiter NAL (any slice types). */
        private val AUD = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())

        fun available(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

        /** ffmpeg 5.1+ has -fps_mode; older ones only -vsync. */
        private val newFfmpeg: Boolean by lazy {
            runCatching {
                val p = ProcessBuilder("ffmpeg", "-hide_banner", "-h", "full").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { r -> r.lineSequence().any { "fps_mode" in it } }.also { p.destroy() }
            }.getOrDefault(true)
        }
    }
}

/** Plays the car's audio channels on the desktop's speakers. */
class AudioOutputs {
    private val lines = ConcurrentHashMap<AudioStreamType, SourceDataLine>()

    fun start(stream: AudioStreamType, cfg: AudioConfiguration) {
        stop(stream)
        runCatching {
            val format = AudioFormat(cfg.samplingRate.toFloat(), cfg.numberOfBits, cfg.numberOfChannels, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, cfg.samplingRate * cfg.numberOfChannels * 2 / 5) // 200 ms buffer
            line.start()
            lines[stream] = line
        }.onFailure { System.err.println("audio: can't open $stream: ${it.message}") }
    }

    fun write(stream: AudioStreamType, data: ByteArray, offset: Int, length: Int) {
        val line = lines[stream] ?: return
        // Never block the protocol thread for long: skip what doesn't fit (the phone paces itself).
        val n = minOf(length, line.available())
        if (n > 0) line.write(data, offset, n - n % 4)
    }

    fun stop(stream: AudioStreamType) {
        lines.remove(stream)?.let { runCatching { it.drain(); it.close() } }
    }

    fun close() = lines.keys.toList().forEach(::stop)
}

/** The desktop microphone as the car's: 16 kHz mono 16-bit, in 20 ms chunks. */
class Microphone(private val sink: (ByteArray, Int) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun open(): Boolean {
        if (running) return true
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val line: TargetDataLine = runCatching { AudioSystem.getTargetDataLine(format).apply { open(format, 6400); start() } }
            .getOrElse { System.err.println("microphone unavailable: ${it.message}"); return false }
        running = true
        thread = Thread({
            val buf = ByteArray(640)
            while (running) {
                val n = line.read(buf, 0, buf.size)
                if (n > 0) sink(buf, n)
            }
            line.stop(); line.close()
        }, "mic").apply { isDaemon = true; start() }
        return true
    }

    fun close() {
        running = false
        thread = null
    }
}

/**
 * Moves an Android emulator's GPS (`adb emu geo fix`), so apps that use the phone's location
 * (like CoMaps) follow the simulated drive too. No-op for real phones.
 */
class EmulatorGps(private val serial: String?) {
    val enabled: Boolean = serial?.startsWith("emulator-") == true

    fun fix(f: Fix) {
        if (!enabled) return
        val cmd = listOf("adb", "-s", serial!!, "emu", "geo", "fix", "%.6f".format(java.util.Locale.US, f.lon), "%.6f".format(java.util.Locale.US, f.lat),
            "100", "12", "%.1f".format(java.util.Locale.US, f.speed * 1.943844)) // speed in knots
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
    }
}
