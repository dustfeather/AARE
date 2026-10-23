package net.prototux.aare.audio

import android.os.Process
import android.os.SystemClock
import android.util.Log
import net.prototux.aare.aap.channels.AudioChannel
import kotlin.math.abs

/**
 * Streams phone audio from a [PcmSource] to one of the car's audio channels (media or
 * navigation guidance).
 *
 * The car's stream is opened when sound starts and closed after a few seconds of silence, so the
 * head unit can go back to its own sources, like the official app does. [Focus] asks the car for
 * the right audio focus around it: full focus for media, a transient ducking one for guidance.
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
                if (!playing && lastSound == now) {
                    focus.request(true)
                    channel.startPlayback()
                    playing = true
                } else if (playing && now - lastSound > SILENCE_MS) {
                    channel.stopPlayback()
                    focus.request(false)
                    playing = false
                }
                if (playing && focus.granted) {
                    channel.sendPcm(buf, 10, chunk, frames * 1_000_000L / rate)
                }
                frames += chunk / (2 * channels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio capture stopped", e)
        } finally {
            if (playing) { runCatching { channel.stopPlayback() }; focus.request(false) }
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
