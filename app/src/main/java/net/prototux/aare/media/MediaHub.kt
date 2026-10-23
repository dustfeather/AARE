package net.prototux.aare.media

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** A media app found on the phone (media3 MediaLibraryService or legacy MediaBrowserService). */
data class MediaApp(
    val packageName: String,
    val service: ComponentName,
    val label: String,
    val icon: Bitmap?,
)

/** What is playing, from whichever media app is active. */
data class PlaybackInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** SystemClock.elapsedRealtime() when positionMs was sampled, to extrapolate progress. */
    val sampledAtMs: Long,
)

/**
 * Media side of the car: discovery, one connection to the active media app, transport controls,
 * and the full-screen browse/now-playing UI.
 *
 * Implemented in this package. All methods are called on the main thread.
 */
interface MediaHub {
    /** Media apps installed on the phone; refreshed on package changes. */
    val apps: StateFlow<List<MediaApp>>

    /** The current playback, from the connected app or any active media session. */
    val playback: StateFlow<PlaybackInfo?>

    /** Connects to [app] (if not already) and makes it the active one. */
    fun select(app: MediaApp)

    fun playPause()
    fun next()
    fun previous()
    fun pause()

    /** Full-screen UI for an app: browse tree (list/grid per content style), search, now playing. */
    @Composable
    fun Screen(app: MediaApp, driving: Boolean, modifier: Modifier)

    /** Disconnects everything. */
    fun release()
}
