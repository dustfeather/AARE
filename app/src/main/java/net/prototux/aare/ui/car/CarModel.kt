package net.prototux.aare.ui.car

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.prototux.aare.notify.PhoneGuidance

/** What kind of app an entry in the launcher is, which decides where its UI goes. */
enum class CarAppKind { NAVIGATION, MEDIA, TEMPLATED, ASSISTANT, MESSAGING, PHONE_SCREEN }

/** One launchable app on the car screen. */
@Stable
class CarAppEntry(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val kind: CarAppKind,
)

@Stable
class NowPlaying(
    val appId: String,
    val title: String,
    val subtitle: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

/** The car UI's navigation: the dashboard, the app grid, or one app full screen. */
sealed interface CarScreen {
    data object Dashboard : CarScreen
    data object Launcher : CarScreen
    data class App(val appId: String) : CarScreen
}

/** A transient card, e.g. an incoming message or a toast from a car app. */
@Stable
class CarNotice(
    val key: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
)

/** Head-unit geometry: the drawable area inside the video margins, and the rail side. */
data class CarGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    /** Rail on the left (left-hand drive) or right. */
    val railStart: Boolean = true,
)

/**
 * All state the car UI reads. The session and hosts write it from the main thread.
 */
@Stable
class CarUiState {
    var night by mutableStateOf(true)
    var screen by mutableStateOf<CarScreen>(CarScreen.Dashboard)
    val apps = mutableStateListOf<CarAppEntry>()
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    /** The navigation app whose map is shown on the dashboard. */
    var activeNavAppId by mutableStateOf<String?>(null)
    /** The media app the dashboard's media card controls. */
    var activeMediaAppId by mutableStateOf<String?>(null)
    val notices = mutableStateListOf<CarNotice>()
    var drivingRestricted by mutableStateOf(false)
    var headUnitName by mutableStateOf("")
    var listening by mutableStateOf(false)
    /** The voice assistant showing as a bar over the screen (an app entry id), or null. */
    var assistantAppId by mutableStateOf<String?>(null)
    /** Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…). */
    var phoneGuidance by mutableStateOf<PhoneGuidance?>(null)
    /** Right-hand drive: the rail goes to the right, next to the driver. */
    var railOnRight by mutableStateOf(false)
    /** The driver sits on the right (right-hand drive): the voice button goes on that side. */
    var driverOnRight by mutableStateOf(false)
    /** The rail runs along the bottom instead of a side. */
    var railBottom by mutableStateOf(false)

    fun app(id: String?): CarAppEntry? = apps.firstOrNull { it.id == id }
}

/** Actions the car UI asks for; implemented by the session. */
interface CarActions {
    fun openApp(app: CarAppEntry)
    fun goHome()
    fun openLauncher()
    fun assistant()
    fun mediaPlayPause()
    fun mediaNext()
    fun mediaPrevious()
    fun dismissNotice(notice: CarNotice)
}
