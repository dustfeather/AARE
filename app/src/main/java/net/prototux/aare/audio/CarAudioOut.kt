package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import net.prototux.aare.aap.channels.AudioChannel
import kotlin.math.abs

/**
 * Streams what the phone plays to the car's media channel.
 *
 * Android only lets a normal app capture other apps' playback through AudioPlaybackCapture,
 * which needs a MediaProjection (one consent from the user) and covers the media, game and
 * unknown usages: music, podcasts, and Lapin's answers in car mode. Navigation prompts
 * (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) can only be captured by a privileged app; on a normal
 * install they keep playing on the phone or its Bluetooth link.
 *
 * The car's media session is opened when sound starts and closed after a few seconds of silence,
 * so the head unit can go back to its own sources, like the official app does.
 */
class CarAudioOut(
    private val projection: MediaProjection,
    private val channel: AudioChannel,
    private val focus: Focus,
) {
    interface Focus {
        /** Ask the car for audio focus (true = we're about to play). */
        fun request(gain: Boolean)
        /** Whether the car currently lets us play. */
        val granted: Boolean
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val cfg = channel.config ?: return
        val rate = cfg.samplingRate
        val channels = cfg.numberOfChannels
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
            .build()
        // ~20 ms chunks: small enough for low latency, big enough to keep the message rate sane.
        val chunk = rate / 50 * channels * 2
        val record = try {
            AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(capture)
                .setBufferSizeInBytes(maxOf(chunk * 8, AudioRecord.getMinBufferSize(rate, format.channelMask, format.encoding)))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "playback capture unavailable", e)
            return
        }
        running = true
        thread = Thread({ loop(record, chunk, rate, channels) }, "aare-audio-out").apply { start() }
    }

    private fun loop(record: AudioRecord, chunk: Int, rate: Int, channels: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(10 + chunk)
        var lastSound = 0L
        var playing = false
        var frames = 0L
        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < chunk && running) {
                    val n = record.read(buf, 10 + got, chunk - got)
                    if (n <= 0) break
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
            runCatching { record.stop() }
            record.release()
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
