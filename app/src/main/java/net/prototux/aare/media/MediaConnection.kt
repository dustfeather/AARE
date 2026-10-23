package net.prototux.aare.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Our one media3 [MediaBrowser] connection to [app], which works for media3 MediaLibraryService
 * and legacy MediaBrowserService(Compat) apps alike. Main thread only.
 *
 * It reconnects when the app's process dies, exposes the library root and paged browse/search,
 * and is a [PlaybackSource] for the now-playing view.
 */
@OptIn(UnstableApi::class)
internal class MediaConnection(
    private val context: Context,
    parent: CoroutineScope,
    val app: MediaApp,
    private val art: ArtLoader,
) : PlaybackSource {

    sealed interface Status {
        data object Connecting : Status
        data class Ready(val root: MediaItem, val hints: StyleHints, val searchSupported: Boolean) : Status
        data class Failed(val error: MediaError) : Status
    }

    override val key = "browser:${app.packageName}"
    override val packageName: String = app.packageName
    override val isConnection = true

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _state = MutableStateFlow(SourceState())
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private var browser: MediaBrowser? = null
    private var connectJob: Job? = null
    private var reconnects = 0
    private var released = false
    private val searchDone = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var artKey: String? = null
    private var artBitmap: Bitmap? = null
    private var artJob: Job? = null
    private var notice: MediaError? = null
    private var noticeJob: Job? = null

    /** Connects if not connected or connecting; safe to call often. */
    fun connect() {
        if (released || connectJob?.isActive == true || browser?.isConnected == true) return
        connectJob = scope.launch { doConnect() }
    }

    /** User asked to try again: reload the root if connected, else reconnect. */
    fun retry() {
        reconnects = 0
        val b = browser
        if (b != null && b.isConnected) scope.launch { loadRoot(b) } else connect()
    }

    private suspend fun doConnect() {
        _status.value = Status.Connecting
        val future = try {
            MediaBrowser.Builder(context, SessionToken(context, app.service))
                .setConnectionHints(rootHints())
                .setListener(listener)
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync()
        } catch (e: IllegalArgumentException) {
            // the service is gone (app updated or disabled since discovery)
            fail("${app.label} has no media service")
            return
        }
        val b = try {
            withTimeout(CONNECT_TIMEOUT_MS) { future.await() }
        } catch (e: TimeoutCancellationException) {
            MediaController.releaseFuture(future)
            fail("${app.label} is not responding")
            return
        } catch (e: CancellationException) {
            MediaController.releaseFuture(future)
            throw e
        } catch (e: Exception) {
            // SecurityException: the app rejected us (caller validation); not worth retrying alone
            Log.w(TAG, "connect ${app.packageName} failed: $e")
            MediaController.releaseFuture(future)
            fail(if (e is SecurityException) "${app.label} did not accept the connection" else "Could not connect to ${app.label}")
            return
        }
        if (released) {
            b.release()
            return
        }
        browser = b
        b.addListener(playerListener)
        updateState()
        loadRoot(b)
    }

    private suspend fun loadRoot(b: MediaBrowser) {
        // Older apps (MediaBrowserService): media3 turns LibraryParams into root hints that always
        // carry EXTRA_RECENT/OFFLINE/SUGGESTED, even false, and VLC then answers with its one-item
        // "suggested" root. Without params media3 sends none of them; browsing then goes through
        // the connection made with our hints (rootHints, given as connection hints).
        val legacy = b.connectedToken?.type != SessionToken.TYPE_LIBRARY_SERVICE
        val params = if (legacy) null else LibraryParams.Builder().setExtras(rootHints()).build()
        val result = try {
            b.getLibraryRoot(params).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Could not open ${app.label}")
            return
        }
        val root = result.value
        if (result.resultCode != LibraryResult.RESULT_SUCCESS || root == null) {
            _status.value = Status.Failed(errorOf(result))
            return
        }
        reconnects = 0
        // root hints may come in the result's params (media3, legacy root extras) or on the root item
        val extras = Bundle()
        result.params?.extras?.let(extras::putAll)
        root.mediaMetadata.extras?.let(extras::putAll)
        _status.value = Status.Ready(root, hintsOf(extras), extras.getBoolean(MediaKeys.SEARCH_SUPPORTED, false))
    }

    private fun fail(message: String) {
        _status.value = Status.Failed(MediaError(message))
    }

    /** One page of [parentId]'s children. Throws [BrowseException]. */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val b = connected()
        val result = try {
            b.getChildren(parentId, page, pageSize, null).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BrowseException(MediaError("Could not load this list"))
        }
        if (result.resultCode != LibraryResult.RESULT_SUCCESS) throw BrowseException(errorOf(result))
        return result.value.orEmpty()
    }

    /**
     * Searches: media3 delivers results in two steps, `search()` then `onSearchResultChanged`, then
     * `getSearchResult()`. Some apps never notify, so we fetch anyway after a short wait.
     */
    suspend fun search(query: String, pageSize: Int): List<MediaItem> = coroutineScope {
        val b = connected()
        val notified = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(SEARCH_NOTIFY_TIMEOUT_MS) { searchDone.first { it == query } }
        }
        val started = try {
            b.search(query, null).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BrowseException(MediaError("Search failed"))
        }
        if (started.resultCode != LibraryResult.RESULT_SUCCESS) {
            notified.cancel()
            throw BrowseException(errorOf(started))
        }
        notified.await()
        val result = try {
            b.getSearchResult(query, 0, pageSize, null).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BrowseException(MediaError("Search failed"))
        }
        if (result.resultCode != LibraryResult.RESULT_SUCCESS) throw BrowseException(errorOf(result))
        result.value.orEmpty()
    }

    /**
     * Plays a browse item the way Android Auto does (playFromMediaId): we hand the app only the
     * chosen item and its session resolves it (onAddMediaItems/onSetMediaItems) and builds the
     * queue itself (rest of the album, playlist...). Passing our own list as the queue would bypass
     * the app's logic and make it resolve every item.
     */
    fun play(item: MediaItem) {
        val b = browser ?: return connect()
        b.setMediaItem(item)
        b.prepare()
        b.play()
    }

    private fun connected(): MediaBrowser =
        browser?.takeIf { it.isConnected } ?: throw BrowseException(MediaError("${app.label} is not connected"))

    // ------------------------------------------------------------- transport (PlaybackSource)

    override fun playPause() {
        browser?.let { Util.handlePlayPauseButtonAction(it) } ?: connect()
    }

    override fun pause() {
        browser?.pause()
    }

    override fun next() {
        browser?.seekToNext()
    }

    override fun previous() {
        browser?.seekToPrevious()
    }

    override fun seekTo(positionMs: Long) {
        browser?.seekTo(positionMs)
    }

    override fun skipTo(entry: QueueEntry) {
        browser?.seekToDefaultPosition(entry.id.toInt())
    }

    // ------------------------------------------------------------- state

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateState()
    }

    private val listener = object : MediaBrowser.Listener {
        override fun onDisconnected(controller: MediaController) = onLost(controller)

        override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: LibraryParams?) {
            searchDone.tryEmit(query)
        }

        override fun onMediaButtonPreferencesChanged(controller: MediaController, mediaButtonPreferences: MutableList<CommandButton>) = updateState()

        override fun onAvailableSessionCommandsChanged(controller: MediaController, commands: androidx.media3.session.SessionCommands) = updateState()

        /** Non-fatal errors the app sends to controllers (e.g. "sign in"); shown for a while. */
        override fun onError(controller: MediaController, sessionError: SessionError) {
            notice = MediaError(sessionError.message.takeIf { it.isNotBlank() && it != DEFAULT_SESSION_MESSAGE }
                ?: errorText(sessionError.code, app.label))
            updateState()
            noticeJob?.cancel()
            noticeJob = scope.launch {
                delay(NOTICE_MS)
                notice = null
                updateState()
            }
        }
    }

    /** The app's process died or it dropped us: reconnect with backoff, as long as we are wanted. */
    private fun onLost(controller: MediaController) {
        if (controller !== browser) return
        Log.i(TAG, "${app.packageName} disconnected")
        controller.release()
        browser = null
        updateState()
        if (released) return
        if (reconnects >= MAX_RECONNECTS) {
            fail("${app.label} stopped")
            return
        }
        _status.value = Status.Connecting
        val wait = 1_000L shl reconnects++
        connectJob = scope.launch {
            delay(wait)
            doConnect()
        }
    }

    private fun updateState() {
        val b = browser
        if (b == null || !b.isConnected) {
            _state.value = SourceState(error = notice)
            return
        }
        val md = b.mediaMetadata
        val hasMedia = b.currentMediaItem != null || md.title != null
        val info = if (!hasMedia) null else PlaybackInfo(
            packageName = packageName,
            title = (md.title ?: md.displayTitle ?: "").toString(),
            artist = (md.artist ?: md.albumArtist ?: md.subtitle ?: "").toString(),
            album = (md.albumTitle ?: "").toString(),
            art = artFor(md),
            playing = b.isPlaying,
            positionMs = b.currentPosition.coerceAtLeast(0),
            durationMs = b.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            sampledAtMs = SystemClock.elapsedRealtime(),
        )
        val error = b.playerError?.let { e ->
            val (label, intent) = errorAction(e.extras)
            MediaError(e.message?.takeIf { it.isNotBlank() } ?: errorText(e.errorCode, app.label), label, intent)
        } ?: notice
        _state.value = SourceState(
            info = info,
            showPause = !Util.shouldShowPlayButton(b),
            canPrevious = b.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            canNext = b.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
            canSeek = b.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && b.isCurrentMediaItemSeekable,
            actions = actionsOf(b),
            queue = queueOf(b),
            error = error,
        )
    }

    /** Current art, loading it in the background when the metadata points at new art. */
    private fun artFor(md: MediaMetadata): Bitmap? {
        val key = ArtLoader.artKey(md.artworkUri, md.artworkData, MediaKeys.ART_SIZE_PX)
        if (key == artKey) return artBitmap
        artKey = key
        artBitmap = art.cached(key)
        artJob?.cancel()
        if (key != null && artBitmap == null) {
            val uri = md.artworkUri
            val data = md.artworkData
            artJob = scope.launch {
                val bmp = art.art(uri, data)
                if (artKey == key && bmp != null) {
                    artBitmap = bmp
                    updateState()
                }
            }
        }
        return artBitmap
    }

    /** App buttons beyond play/pause/previous/next (like, shuffle, speed...). */
    private fun actionsOf(b: MediaBrowser): List<CustomAction> =
        b.mediaButtonPreferences
            .filter { it.isEnabled && (it.sessionCommand != null || it.playerCommand !in TRANSPORT_COMMANDS) }
            .take(MAX_ACTIONS)
            .mapIndexed { i, button ->
                val own = CommandButton.getIconResIdForIconConstant(button.icon)
                val icon = when {
                    own != 0 -> IconRef(null, own)
                    button.iconResId != 0 -> IconRef(packageName, button.iconResId)
                    button.iconUri != null -> IconRef(null, 0, button.iconUri)
                    else -> null
                }
                CustomAction(
                    id = button.sessionCommand?.customAction ?: "player:${button.playerCommand}:$i",
                    label = button.displayName.toString(),
                    icon = icon,
                    run = { browser?.let(button::executeAction) },
                )
            }

    /** The play queue around the current item (huge playlists are cut). */
    private fun queueOf(b: MediaBrowser): List<QueueEntry> {
        if (!b.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyList()
        val timeline = b.currentTimeline
        if (timeline.isEmpty || timeline.windowCount < 2) return emptyList()
        val current = b.currentMediaItemIndex
        val start = (current - QUEUE_BEFORE).coerceAtLeast(0)
        val end = (start + MAX_QUEUE).coerceAtMost(timeline.windowCount)
        val window = Timeline.Window()
        return (start until end).map { i ->
            val item = timeline.getWindow(i, window).mediaItem
            QueueEntry(i.toLong(), item.titleText, item.subtitleText, i == current)
        }
    }

    private fun errorOf(result: LibraryResult<*>): MediaError {
        val se = result.sessionError
        val message = se?.message?.takeIf { it.isNotBlank() && it != DEFAULT_SESSION_MESSAGE } ?: errorText(result.resultCode, app.label)
        val (label, intent) = errorAction(se?.extras)
        return MediaError(message, label, intent)
    }

    /** Disconnects for good. */
    fun release() {
        released = true
        scope.cancel()
        browser?.release()
        browser = null
    }

    companion object {
        private const val TAG = "AareMedia"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SEARCH_NOTIFY_TIMEOUT_MS = 3_000L
        private const val NOTICE_MS = 10_000L
        private const val MAX_RECONNECTS = 5
        private const val MAX_ACTIONS = 4
        private const val MAX_QUEUE = 200
        private const val QUEUE_BEFORE = 20

        /** media3's placeholder message when a session sends an error without one. */
        private const val DEFAULT_SESSION_MESSAGE = "no error message provided"

        private val TRANSPORT_COMMANDS = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )

        /** What Android Auto tells media apps about itself, as connection and root hints. */
        fun rootHints() = Bundle().apply {
            putInt(MediaKeys.ROOT_CHILDREN_LIMIT, MediaKeys.ROOT_CHILDREN_LIMIT_VALUE)
            putInt(MediaKeys.ROOT_CHILDREN_SUPPORTED_FLAGS, MediaKeys.FLAG_BROWSABLE)
            putInt(MediaKeys.MEDIA_ART_SIZE_PIXELS, MediaKeys.ART_SIZE_PX)
            putInt(MediaKeys.CUSTOM_BROWSER_ACTION_LIMIT, 0)
            // No EXTRA_RECENT/OFFLINE/SUGGESTED: Android Auto only sends them to ask for those special
            // roots, and some apps (VLC) only check that the key is there, not its value.
        }
    }
}
