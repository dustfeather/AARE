package net.prototux.aare.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.location.Location
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.navigation.model.Trip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.car.Calls
import net.prototux.aare.media.MediaHub
import net.prototux.aare.mirror.MirrorScreen
import net.prototux.aare.notify.CarNotificationListener
import net.prototux.aare.notify.MessagingScreen
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.car.CarActions
import net.prototux.aare.ui.car.CarAppEntry
import net.prototux.aare.ui.car.CarAppKind
import net.prototux.aare.ui.car.CarCall
import net.prototux.aare.ui.car.CarNotice
import net.prototux.aare.ui.car.CarScreen
import net.prototux.aare.ui.car.CarSettingsScreen
import net.prototux.aare.ui.car.PhoneScreen
import net.prototux.aare.ui.car.CarShell
import net.prototux.aare.ui.car.CarUiState
import net.prototux.aare.ui.car.NowPlaying
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Everything on the car screen that doesn't depend on how it reaches the car: the app list,
 * hosted car apps, media, notices and the root composable. A projection session (real head unit)
 * and the on-phone preview both drive one of these.
 *
 * Main thread only, except the properties documented as thread-safe for [HostEnvironment].
 */
class CarRuntime(
    val context: Context,
    private val scope: CoroutineScope,
    val settings: Settings,
    val media: MediaHub,
    /** Release [media] in [stop]: true when this runtime created it (the preview), false when shared. */
    private val ownsMedia: Boolean = true,
) : HostEnvironment {

    val ui = CarUiState()
    val icons = IconLoader(context)
    private val main = Handler(Looper.getMainLooper())

    /** Hosted car app connections by entry id. */
    private val connections = mutableStateMapOf<String, CarAppConnection>()
    private val carApps = HashMap<String, CarAppInfo>()
    private val visibleCount = HashMap<CarAppConnection, Int>()

    /** Hooks the projection session sets: navigation status to the cluster, microphone, etc. */
    var onTrip: ((CarAppConnection, Trip?) -> Unit)? = null
    var microphone: ((sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> MicHandle?)? = null
    var onAssistant: (() -> Unit)? = null
    /** The screen-capture consent (car audio), which also lets the phone screen be mirrored. */
    var projection: () -> MediaProjection? = { null }
    /** Guidance from a navigation app on the phone (Google Maps, Waze…) changed, or ended (null). */
    var onPhoneGuidance: ((PhoneGuidance?) -> Unit)? = null

    // ------------------------------------------------------------------ HostEnvironment

    override val driving: Boolean get() = ui.drivingRestricted
    override val night: Boolean get() = ui.night
    override var screenWidthDp by mutableIntStateOf(1280)
    override var screenHeightDp by mutableIntStateOf(720)
    override var densityDpi by mutableIntStateOf(160)
    @Volatile override var carLocation: Location? = null
    @Volatile override var carHeading: Float? = null

    override fun contentLimit(type: Int): Int = when (type) {
        // Android Auto's usual limits: generous when parked, shorter lists while driving.
        ConstraintManager.CONTENT_LIMIT_TYPE_LIST -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_GRID -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST -> 6
        ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST -> 3
        ConstraintManager.CONTENT_LIMIT_TYPE_PANE -> 4
        else -> 6
    }

    override fun toast(app: CarAppConnection, text: CharSequence, long: Boolean) {
        notice(app.info.label, text.toString(), app.info.icon, if (long) 6_000 else 3_500)
    }

    override fun startCarApp(from: CarAppConnection, intent: Intent) {
        val action = intent.action
        val isNavigate = action == CarContext.ACTION_NAVIGATE || intent.data?.scheme == "geo" || intent.data?.scheme == "google.navigation"
        val target = when {
            intent.component != null -> connections.values.firstOrNull { it.info.service == intent.component }
                ?: carApps.entries.firstOrNull { it.value.service == intent.component }?.let { connection(it.key) }
            isNavigate -> navConnection()
            else -> null
        }
        if (target == null) {
            notice(from.info.label, if (isNavigate) "No navigation app on the phone" else "Can't open that from the car")
            return
        }
        val navIntent = if (isNavigate && intent.action != CarContext.ACTION_NAVIGATE) Intent(CarContext.ACTION_NAVIGATE, intent.data) else intent
        target.connect(navIntent)
        show(target)
    }

    override fun finish(app: CarAppConnection) {
        val id = idOf(app) ?: return
        if ((ui.screen as? CarScreen.App)?.appId == id) ui.screen = CarScreen.Dashboard
        if (ui.activeNavAppId != id) {
            app.release()
            connections.remove(id)
        }
    }

    override fun navigationStarted(app: CarAppConnection) {
        idOf(app)?.let { ui.activeNavAppId = it; settings.lastNavApp = app.info.packageName }
    }

    override fun navigationEnded(app: CarAppConnection) {
        onTrip?.invoke(app, null)
    }

    override fun tripUpdated(app: CarAppConnection, trip: Trip) {
        onTrip?.invoke(app, trip)
    }

    override fun openMicrophone(app: CarAppConnection, sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit): MicHandle? {
        // The rail's mic and the assistant bar show when an app listens.
        val handle = microphone?.invoke(sink) { main.post { ui.listening = false }; onStopped() } ?: return null
        main.post { ui.listening = true }
        return MicHandle { handle.close(); main.post { ui.listening = false } }
    }

    // ------------------------------------------------------------------ car screen settings

    /** The car's own day/night signal, if it sent one. */
    private var carNight: Boolean? = null
    /** The head unit's driver position. */
    var rightHandDrive = false
        set(v) { field = v; applyScreenSettings() }

    /** The car's driving signal (parked or not), before the passenger-mode setting. */
    private var carDriving = false

    /** The car says it's moving (true) or parked. */
    fun carDriving(driving: Boolean) {
        carDriving = driving
        applyScreenSettings()
    }

    /** The car's day/night signal changed. */
    fun carNightMode(night: Boolean) {
        carNight = night
        applyScreenSettings()
    }

    /** Applies the theme and bar position settings (also when they change during a drive). */
    private fun applyScreenSettings() {
        val night = when (settings.carTheme) {
            Settings.THEME_DARK -> true
            Settings.THEME_LIGHT -> false
            else -> carNight ?: true
        }
        if (ui.night != night) {
            ui.night = night
            configurationChanged()
        }
        val position = settings.railPosition
        // Passenger mode lifts the driving restrictions (keyboard, list lengths, phone screen).
        ui.drivingRestricted = carDriving && !settings.ignoreDriving
        ui.carPlayStyle = settings.carStyle == Settings.STYLE_CARPLAY
        ui.darkBar = settings.darkBar
        ui.railBottom = position == Settings.RAIL_BOTTOM
        ui.driverOnRight = rightHandDrive
        ui.railOnRight = when (position) {
            Settings.RAIL_LEFT -> false
            Settings.RAIL_RIGHT -> true
            else -> rightHandDrive
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "car_theme", "rail_position", "car_style", "dark_bar", "ignore_driving" -> main.post { applyScreenSettings() }
            // Apps shown or hidden from the phone: update the car's app list now.
            "hidden_apps", "phone_screen", "assistant_pkg" -> main.post { refreshApps() }
        }
    }

    // ------------------------------------------------------------------ apps

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    fun start() {
        active = this
        applyScreenSettings()
        settings.registerListener(settingsListener)
        refreshApps()
        context.registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        scope.launch { media.apps.collectLatest { refreshApps() } }
        scope.launch { watchCalls() }
        scope.launch {
            CarNotificationListener.guidance.collect { g ->
                // A navigation app hosted on the car screen shows its own guidance.
                val shown = g?.takeUnless { p -> ui.apps.any { it.kind == CarAppKind.NAVIGATION && it.packageName == p.packageName } }
                ui.phoneGuidance = shown
                onPhoneGuidance?.invoke(shown)
            }
        }
        scope.launch {
            media.playback.collectLatest { p ->
                ui.nowPlaying = p?.let {
                    NowPlaying(mediaId(it.packageName), it.title, listOf(it.artist, it.album).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        it.art, it.playing, it.positionMs, it.durationMs, it.sampledAtMs)
                }
                if (p != null && ui.activeMediaAppId == null) ui.activeMediaAppId = mediaId(p.packageName)
            }
        }
    }

    fun stop() {
        if (active === this) active = null
        runCatching { context.unregisterReceiver(packageReceiver) }
        settings.unregisterListener(settingsListener)
        connections.values.forEach { it.release() }
        connections.clear()
        if (ownsMedia) media.release()
    }

    /** Rescans car apps (package manager work and icons on a background thread). */
    fun refreshApps() {
        scope.launch {
            val (found, messaging) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                CarAppCatalog.query(context) to AndroidAutoApps.messagingApps(context)
            }
            applyApps(found, messaging)
        }
    }

    private fun applyApps(found: List<CarAppInfo>, messaging: List<PhoneApp>) {
        val hidden = settings.hiddenApps
        val assistantPkg = assistantPackage()
        carApps.clear()
        val entries = ArrayList<CarAppEntry>()
        for (app in found) {
            val id = carId(app)
            carApps[id] = app
            if (app.packageName in hidden) continue
            val kind = when {
                app.isNavigation -> CarAppKind.NAVIGATION
                app.packageName == assistantPkg -> CarAppKind.ASSISTANT
                else -> CarAppKind.TEMPLATED
            }
            entries += CarAppEntry(id, app.packageName, app.label, app.icon, kind)
        }
        for (m in media.apps.value) {
            // An app that has a car UI of its own is shown once, as that.
            if (m.packageName in hidden || entries.any { it.packageName == m.packageName }) continue
            entries += CarAppEntry(mediaId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MEDIA)
        }
        // Built in, like Android Auto's: calls and the car-side settings.
        entries += CarAppEntry(PHONE_ID, context.packageName, "Phone", builtinIcon(R.drawable.ic_car_phone), CarAppKind.PHONE)
        entries += CarAppEntry(SETTINGS_ID, context.packageName, "Settings", builtinIcon(R.drawable.ic_car_settings), CarAppKind.SETTINGS)
        if (settings.phoneScreen) {
            entries += CarAppEntry(PHONE_SCREEN_ID, context.packageName, "Phone screen", builtinIcon(R.drawable.ic_phone_screen), CarAppKind.PHONE_SCREEN)
        }
        for (m in messaging) {
            if (m.packageName in hidden || entries.any { it.packageName == m.packageName }) continue
            entries += CarAppEntry(messagingId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MESSAGING)
        }
        ui.apps.clear()
        ui.apps.addAll(entries)
        // Restore the last navigation and media apps, so the dashboard isn't empty.
        if (ui.activeNavAppId == null) {
            val nav = entries.filter { it.kind == CarAppKind.NAVIGATION }
            ui.activeNavAppId = (nav.firstOrNull { it.packageName == settings.lastNavApp } ?: nav.singleOrNull())?.id
        }
        if (ui.activeMediaAppId == null) {
            ui.activeMediaAppId = entries.firstOrNull { it.kind == CarAppKind.MEDIA && it.packageName == settings.lastMediaApp }?.id
        }
    }

    /** Phone calls for the car's call card: polled, as no telephony listener needs fewer permissions. */
    private suspend fun watchCalls() {
        var since = 0L
        var last = TelephonyManager.CALL_STATE_IDLE
        while (true) {
            val state = Calls.state(context)
            if (state != last) { since = System.currentTimeMillis(); last = state }
            val name = CarNotificationListener.caller.value
            val call = when (state) {
                TelephonyManager.CALL_STATE_RINGING -> CarCall(ringing = true, name = name ?: "Incoming call", sinceMs = since)
                TelephonyManager.CALL_STATE_OFFHOOK -> CarCall(ringing = false, name = name ?: "Call", sinceMs = since)
                else -> null
            }
            if (call != ui.call) ui.call = call
            kotlinx.coroutines.delay(if (state == TelephonyManager.CALL_STATE_IDLE) 1_500 else 500)
        }
    }

    /** Starts the last media app playing again (the "resume music" setting). */
    fun resumeMedia() {
        scope.launch {
            // The app list and the media apps come in asynchronously: give them a moment.
            kotlinx.coroutines.delay(1_500)
            val app = media.apps.value.firstOrNull { it.packageName == settings.lastMediaApp } ?: return@launch
            media.select(app)
            kotlinx.coroutines.delay(1_500)
            if (media.playback.value?.playing != true) media.playPause()
        }
    }

    private fun builtinIcon(res: Int) = ContextCompat.getDrawable(context, res)?.toBitmap(128, 128)

    /** Packages of the navigation apps hosted on the car screen. */
    fun navigationPackages(): Set<String> = carApps.values.filter { it.isNavigation }.mapTo(HashSet()) { it.packageName }

    /** The connection for a car app entry, created on first use. */
    fun connection(id: String): CarAppConnection? {
        connections[id]?.let { return it }
        val info = carApps[id] ?: return null
        return CarAppConnection(context, info, this).also { connections[id] = it }
    }

    private fun navConnection(): CarAppConnection? {
        val id = ui.activeNavAppId ?: ui.apps.firstOrNull { it.kind == CarAppKind.NAVIGATION }?.id ?: return null
        ui.activeNavAppId = id
        return connection(id)
    }

    private fun idOf(conn: CarAppConnection): String? = connections.entries.firstOrNull { it.value === conn }?.key

    private fun show(conn: CarAppConnection) {
        idOf(conn)?.let { ui.screen = CarScreen.App(it) }
    }

    fun acquireVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 0) + 1
        visibleCount[conn] = n
        if (n == 1) conn.setVisible(true)
    }

    fun releaseVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 1) - 1
        if (n <= 0) {
            visibleCount.remove(conn)
            // Let the other copy (dashboard card ↔ full screen) take over before stopping the app.
            main.postDelayed({ if ((visibleCount[conn] ?: 0) == 0) conn.setVisible(false) }, 500)
        } else visibleCount[conn] = n
    }

    /** Pushes car GPS/compass to subscribed apps. Any thread. */
    fun carSensorsChanged() = main.post { connections.values.forEach { it.carSensorsChanged() } }

    /** Night mode or screen size changed: tell running apps. */
    fun configurationChanged() = connections.values.forEach { it.configurationChanged() }

    // ------------------------------------------------------------------ notices

    fun notice(title: String, text: String, icon: Bitmap? = null, durationMs: Long = 4_000, actions: List<Pair<String, () -> Unit>> = emptyList()) {
        val n = CarNotice("n${SystemClock.uptimeMillis()}", title, text, icon, actions)
        ui.notices.add(n)
        main.postDelayed({ ui.notices.remove(n) }, durationMs)
    }

    // ------------------------------------------------------------------ actions from the car UI

    val actions = object : CarActions {
        override fun openApp(app: CarAppEntry) {
            when (app.kind) {
                CarAppKind.MEDIA -> {
                    ui.activeMediaAppId = app.id
                    settings.lastMediaApp = app.packageName
                    media.apps.value.firstOrNull { it.packageName == app.packageName }?.let { media.select(it) }
                }
                CarAppKind.NAVIGATION -> {
                    ui.activeNavAppId = app.id
                    settings.lastNavApp = app.packageName
                    connection(app.id)?.connect()
                }
                else -> connection(app.id)?.connect()
            }
            ui.screen = CarScreen.App(app.id)
            ui.recentAppIds.remove(app.id)
            ui.recentAppIds.add(0, app.id)
            while (ui.recentAppIds.size > 6) ui.recentAppIds.removeAt(ui.recentAppIds.lastIndex)
        }

        override fun goHome() { ui.screen = CarScreen.Dashboard }
        override fun openLauncher() { ui.screen = CarScreen.Launcher }
        override fun assistant() { this@CarRuntime.assistant() }
        override fun mediaPlayPause() = media.playPause()
        override fun mediaNext() = media.next()
        override fun mediaPrevious() = media.previous()
        override fun dismissNotice(notice: CarNotice) { ui.notices.remove(notice) }
        override fun answerCall() = Calls.answer(context)
        override fun endCall() = Calls.end(context)
    }

    /** The chosen assistant's package, or the phone's default assistant. */
    private fun assistantPackage(): String? = settings.assistantPackage ?: Assistants.phoneDefault(context)

    /**
     * Steering-wheel voice button or the rail's mic. An assistant with a car app (Lapin, for example)
     * opens on the car screen and is asked to listen with ACTION_VOICE_COMMAND; any other assistant
     * opens on the phone.
     */
    fun assistant() {
        onAssistant?.invoke()
        val pkg = assistantPackage()
        if (pkg == null) {
            notice("Assistant", "Choose a voice assistant in AARE's settings on the phone")
            return
        }
        val entry = ui.apps.firstOrNull { it.packageName == pkg && it.kind != CarAppKind.MEDIA && it.kind != CarAppKind.NAVIGATION }
        val conn = entry?.let { connection(it.id) }
        if (entry != null && conn != null) {
            // Like Android Auto's assistant: a bar over whatever is on screen, not the app full screen.
            conn.connect(Intent(Intent.ACTION_VOICE_COMMAND).setComponent(conn.info.service))
            if ((ui.screen as? CarScreen.App)?.appId != entry.id) ui.assistantAppId = entry.id
            return
        }
        val label = Assistants.label(context, pkg) ?: pkg
        if (Assistants.startOnPhone(context, pkg)) {
            notice("Assistant", "$label has no car screen: it opened on the phone")
        } else {
            notice("Assistant", "$label can't be opened. Choose another assistant in AARE's settings")
        }
    }

    /** Set by a screen with its own back stack (a conversation in a messaging app): true if it handled back. */
    var screenBack: (() -> Boolean)? = null

    /** Back key from the car. */
    fun back() {
        if (ui.assistantAppId != null) { ui.assistantAppId = null; return }
        if (screenBack?.invoke() == true) return
        when (val s = ui.screen) {
            is CarScreen.App -> {
                val conn = connections[s.appId]
                if (conn != null) conn.back() else ui.screen = CarScreen.Dashboard
            }
            CarScreen.Launcher -> ui.screen = CarScreen.Dashboard
            CarScreen.Dashboard -> Unit
        }
    }

    // ------------------------------------------------------------------ UI

    /** The whole car screen. */
    @Composable
    fun Root(modifier: Modifier = Modifier) {
        CarTheme(night = ui.night) {
            CompositionLocalProvider(LocalCarDimens provides LocalCarDimens.current) {
                CarShell(
                    state = ui,
                    actions = actions,
                    appContent = { app, mod ->
                        when (app.kind) {
                            CarAppKind.MEDIA -> {
                                val m = media.apps.value.firstOrNull { it.packageName == app.packageName }
                                if (m != null) media.Screen(m, ui.drivingRestricted, mod)
                            }
                            CarAppKind.MESSAGING -> MessagingScreen(app, ui.drivingRestricted, this, mod)
                            CarAppKind.PHONE_SCREEN -> MirrorScreen(this, ui.drivingRestricted, mod)
                            CarAppKind.PHONE -> PhoneScreen(this, mod)
                            CarAppKind.SETTINGS -> CarSettingsScreen(settings, mod)
                            else -> connection(app.id)?.let { CarAppScreen(it, this, mod) }
                        }
                    },
                    navMap = { app, mod -> connection(app.id)?.let { CarAppScreen(it, this, mod, compact = true) } },
                    overlay = { mod ->
                        val entry = ui.app(ui.assistantAppId)
                        val conn = entry?.let { connection(it.id) }
                        if (entry != null && conn != null) {
                            AssistantBar(entry, conn, this, mod,
                                onClose = { ui.assistantAppId = null },
                                onExpand = { ui.assistantAppId = null; ui.screen = CarScreen.App(entry.id) })
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val TAG = "AareRuntime"
        fun carId(app: CarAppInfo) = "car:${app.service.flattenToShortString()}"
        fun mediaId(pkg: String) = "media:$pkg"
        fun messagingId(pkg: String) = "messages:$pkg"
        const val PHONE_SCREEN_ID = "phone-screen"
        const val PHONE_ID = "phone"
        const val SETTINGS_ID = "car-settings"

        /** The car screen being driven now (a car or the preview), for the voice assistant's car tools. Main thread. */
        var active: CarRuntime? = null
            private set
    }
}
