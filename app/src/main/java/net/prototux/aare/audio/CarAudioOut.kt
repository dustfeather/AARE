package net.prototux.aare.audio

import android.os.Process
import android.os.SystemClock
import android.util.Log
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import net.prototux.aare.aap.channels.AudioChannel
import kotlin.math.abs

/**
 * Streams phone audio from a [PcmSource] to one of the car's audio channels (media or
 * navigation guidance).
 *
 * The car's stream is opened when sound starts and closed after a few seconds of silence, so the
 * head unit can go back to its own sources, like the official app does. [Focus] asks the car for
 * the right audio focus around it: full focus for media, a transient ducking one for guidance.
 * The stream starts once the car grants focus; the sound captured meanwhile (up to [MAX_QUEUED]
 * chunks) is kept and sent first, so a navigation prompt doesn't lose its first words.
 */
class CarAudioOut(
    private val source: PcmSource,
    private val channel: AudioChannel,
    private val focus: Focus,
    private val onFailed: () -> Unit = {},
) {
    interface Focus {
        /** About to play (true) or done (false). */
        fun request(playing: Boolean)
        /** Whether the car currently lets us play. */
        val granted: Boolean
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val maxBacklog = if (channel.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE) MAX_QUEUED else LIVE_BACKLOG

    fun start() {
        val cfg = channel.config ?: return
        running = true
        thread = Thread({ loop(cfg.samplingRate, cfg.numberOfChannels) }, "aare-audio-${channel.streamType.name.lowercase()}").apply { start() }
    }

    private fun loop(rate: Int, channels: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (!source.start()) {
            running = false
            source.release()
            onFailed()
            return
        }
        // ~20 ms chunks: small enough for low latency, big enough to keep the message rate sane.
        val chunk = rate / 50 * channels * 2
        val buf = ByteArray(10 + chunk)
        var lastSound = 0L
        var playing = false
        var frames = 0L
        // Chunks (with their timestamp) waiting for focus or for room in the car's ack window.
        val queued = ArrayDeque<Pair<ByteArray, Long>>()
        fun queue(ts: Long) {
            queued.addLast(buf.copyOf() to ts)
            while (queued.size > MAX_QUEUED) queued.removeFirst()
        }
        try {
            while (running) {
                var got = 0
                while (got < chunk && running) {
                    val n = source.read(buf, 10 + got, chunk - got)
                    if (n < 0) { running = false; break }
                    got += n
                }
                if (got < chunk) continue
                val now = SystemClock.elapsedRealtime()
                if (!isSilent(buf, 10, chunk)) lastSound = now
                val ts = frames * 1_000_000L / rate
                frames += chunk / (2 * channels)
                if (!playing && lastSound == now) {
                    focus.request(true)
                    playing = true
                } else if (playing && now - lastSound > SILENCE_MS) {
                    channel.stopPlayback()
                    focus.request(false)
                    playing = false
                    queued.clear()
                }
                if (!playing) continue
                if (!channel.started) {
                    // Keep the sound until the car grants focus, then open the stream.
                    if (!focus.granted) { queue(ts); continue }
                    channel.startPlayback()
                    if (!channel.started) { queue(ts); continue }
                    // Prompts keep their whole start; media keeps only a little, or it would lag
                    // behind real time (video lip sync) for the whole stream.
                    while (queued.size > maxBacklog) queued.removeFirst()
                } else if (!focus.granted) {
                    // Focus lost mid-stream: drop, stale sound later would be worse.
                    queued.clear()
                    continue
                }
                if (queued.isEmpty() && channel.hasRoom) {
                    // A copy: the writer thread sends the array later, and buf is refilled meanwhile.
                    channel.sendPcm(buf.copyOf(), 10, chunk, ts)
                    continue
                }
                // Catching up: send the backlog in order, as fast as the car's ack window allows.
                queue(ts)
                while (queued.isNotEmpty() && channel.hasRoom) queued.removeFirst().let { (b, t) -> channel.sendPcm(b, 10, chunk, t) }
                // The car stays behind (or never acks): stay close to real time. The oldest goes
                // through sendPcm so dropped chunks still count towards ack-less detection.
                while (queued.size > maxBacklog) queued.removeFirst().let { (b, t) -> channel.sendPcm(b, 10, chunk, t) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio capture stopped", e)
        } finally {
            if (playing) { runCatching { channel.stopPlayback() }; focus.request(false) }
            queued.clear()
            source.release()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        private const val TAG = "AareAudioOut"
        private const val SILENCE_MS = 3_000L
        /** About 1 s of 20 ms chunks kept while waiting for the car's focus grant. */
        private const val MAX_QUEUED = 50
        /** Backlog kept once media plays (~240 ms). */
        private const val LIVE_BACKLOG = 12

        /** Digital silence or noise floor (captures deliver zeros when nothing plays). */
        fun isSilent(b: ByteArray, off: Int, len: Int): Boolean {
            var i = off
            val end = off + len - 1
            while (i < end) {
                val s = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
                if (abs(s.toShort().toInt()) > 8) return false
                i += 8 // sampling every 4th sample is plenty to detect sound
            }
            return true
        }
    }
}
