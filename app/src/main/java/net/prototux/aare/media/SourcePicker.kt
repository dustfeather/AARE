package net.prototux.aare.media

import androidx.media3.session.SessionError

/** What [pickSource] needs to know about one playback source. */
data class SourceCandidate(
    val key: String,
    val packageName: String,
    /** Our own media3 connection, as opposed to a session seen through MediaSessionManager. */
    val connected: Boolean,
    val playing: Boolean,
    /** Has something loaded (metadata), even if paused. */
    val hasMedia: Boolean,
)

/**
 * Chooses which source "now playing" and the transport buttons follow.
 *
 * - A playing source wins: our connection first, then the previous pick, then the system's order
 *   (MediaSessionManager lists the most recently active session first).
 * - When nothing plays, stay on the previous pick so pausing never jumps to another app.
 * - Our connection hides the phone session of the same app when it has media: same player,
 *   but media3 gives us richer state (queue, custom layout, errors).
 */
fun pickSource(candidates: List<SourceCandidate>, previousKey: String?): String? {
    val connected = candidates.firstOrNull { it.connected }
    val list = candidates.filter {
        it.connected || connected == null || !connected.hasMedia || it.packageName != connected.packageName
    }
    val previous = list.firstOrNull { it.key == previousKey }
    val playing = list.filter { it.playing }
    if (playing.isNotEmpty()) {
        return (playing.firstOrNull { it.connected } ?: playing.firstOrNull { it.key == previousKey } ?: playing.first()).key
    }
    if (previous != null && previous.hasMedia) return previous.key
    return (list.firstOrNull { it.connected && it.hasMedia } ?: list.firstOrNull { it.hasMedia })?.key
}

/**
 * A driver-friendly message for a media3 [SessionError] / `LibraryResult` code (the session codes
 * equal `PlaybackException.ERROR_CODE_*`), used when the app sent no message of its own.
 */
fun errorText(code: Int, appLabel: String): String = when (code) {
    SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> "Sign in to $appLabel on your phone"
    SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED -> "$appLabel needs a premium account"
    SessionError.ERROR_SESSION_CONCURRENT_STREAM_LIMIT -> "Too many devices are streaming"
    SessionError.ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED -> "Blocked by parental controls"
    SessionError.ERROR_SESSION_NOT_AVAILABLE_IN_REGION -> "Not available in your region"
    SessionError.ERROR_SESSION_SKIP_LIMIT_REACHED -> "Skip limit reached"
    SessionError.ERROR_SESSION_SETUP_REQUIRED -> "Finish setting up $appLabel on your phone"
    SessionError.ERROR_SESSION_END_OF_PLAYLIST -> "End of the playlist"
    SessionError.ERROR_PERMISSION_DENIED -> "$appLabel does not allow AARE to browse it"
    SessionError.ERROR_IO -> "Network or storage problem"
    SessionError.ERROR_NOT_SUPPORTED -> "Not supported by $appLabel"
    SessionError.ERROR_SESSION_DISCONNECTED -> "$appLabel disconnected"
    else -> "$appLabel reported an error ($code)"
}
