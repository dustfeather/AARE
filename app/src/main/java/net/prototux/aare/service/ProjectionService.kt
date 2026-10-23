package net.prototux.aare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.prototux.aare.AndroidAapLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.aap.Transport
import net.prototux.aare.car.CarSession
import net.prototux.aare.cert.CertificateStore
import net.prototux.aare.media.MediaHub
import net.prototux.aare.transport.TcpServer
import net.prototux.aare.transport.UsbAccessoryTransport
import net.prototux.aare.transport.wireless.WirelessConnector
import net.prototux.aare.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the car connection alive: owns at most one [CarSession], the transports that can start
 * one (USB accessory, developer TCP server, wireless) and the audio-capture consent.
 */
class ProjectionService : Service() {

    data class State(
        val running: Boolean = false,
        val connected: Boolean = false,
        val status: String = "Not connected",
        val headUnit: String = "",
        val video: String = "",
        val transport: String = "",
        val certificate: String = "",
        val audioCapture: Boolean = false,
        val lastError: String? = null,
        val log: List<String> = emptyList(),
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private lateinit var certs: CertificateStore
    private lateinit var media: MediaHub
    private var session: CarSession? = null
    private var tcpServer: TcpServer? = null
    private var projection: MediaProjection? = null
    private var usbAccessory: UsbAccessory? = null
    private var usbRetries = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wirelessJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        certs = CertificateStore(this)
        media = MediaHub(this, scope)
        createChannel()
        _state.update { it.copy(running = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_USB -> intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)?.let { openUsb(it) }
            ACTION_WIRELESS -> intent.getStringExtra(EXTRA_DEVICE)?.let { connectWireless(it) }
            ACTION_AUDIO_CONSENT -> acceptAudioConsent(intent)
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
        }
        applyListeners()
        // Nothing to do: go away (USB attach and the Bluetooth receiver start us again when needed).
        main.postDelayed({ if (idle()) stopEverything() }, 3_000)
        return START_STICKY
    }

    override fun onDestroy() {
        wirelessJob?.cancel()
        session?.stop()
        tcpServer?.stop()
        projection?.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        _state.value = State()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ transports

    /** Starts or stops the TCP server according to the settings. */
    private fun applyListeners() {
        if (settings.headUnitServer && tcpServer == null) {
            tcpServer = runCatching {
                TcpServer(lan = settings.headUnitServerLan) { t -> main.post { startSession(t, wireless = true) } }.also { it.start() }
            }.onFailure { log("can't listen on ${TcpServer.PORT}: ${it.message}") }.getOrNull()
            if (tcpServer != null) log("head unit server listening on ${TcpServer.PORT}${if (settings.headUnitServerLan) " (LAN)" else " (loopback)"}")
        } else if (!settings.headUnitServer && tcpServer != null) {
            tcpServer?.stop(); tcpServer = null
        }
    }

    /**
     * Wireless: Bluetooth bootstrap, join the car's Wi-Fi, TCP to its AAP server. Runs in the
     * foreground service because Android only lets foreground apps request Wi-Fi networks.
     */
    private fun connectWireless(address: String) {
        if (session != null || wirelessJob?.isActive == true) return
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        val connector = WirelessConnector(this, AndroidAapLog)
        val missing = connector.missingPermissions()
        if (missing.isNotEmpty()) {
            log("wireless: missing permissions ${missing.joinToString { it.substringAfterLast('.') }}")
            return
        }
        log("wireless: connecting to ${runCatching { device.name }.getOrNull() ?: address}")
        _state.update { it.copy(status = "Connecting wirelessly…") }
        wirelessJob = scope.launch {
            try {
                val transport = connector.connect(device)
                startSession(transport, wireless = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("wireless: ${e.message}")
                _state.update { it.copy(status = "Not connected", lastError = e.message) }
            }
        }
    }

    private fun openUsb(accessory: UsbAccessory) {
        usbAccessory = accessory
        val usb = getSystemService(UsbManager::class.java)
        val transport = runCatching { UsbAccessoryTransport(usb, accessory) }
            .onFailure { log("USB: ${it.message}") }.getOrNull() ?: return
        startSession(transport, wireless = false)
    }

    private fun startSession(transport: Transport, wireless: Boolean) {
        if (session != null) {
            log("already connected; refusing ${transport.name}")
            transport.close()
            return
        }
        val cert = certs.pick(settings.certificateIndex)
        log("connecting ${transport.name} with certificate \"${cert.name}\"")
        acquireWakeLock()
        val s = CarSession(this, transport, cert.identity, settings, media, { projection }, wireless, sessionListener)
        session = s
        _state.update { it.copy(connected = true, transport = transport.name, certificate = cert.name, lastError = null) }
        s.start()
    }

    private val sessionListener = object : CarSession.Listener {
        override fun onStatus(session: CarSession, status: String) {
            log(status)
            _state.update { it.copy(status = status, headUnit = session.headUnitName, video = session.videoDescription) }
            updateNotification(status)
        }

        override fun onEnded(session: CarSession, reason: String, certificateRejected: Boolean) {
            if (this@ProjectionService.session !== session) return
            this@ProjectionService.session = null
            releaseWakeLock()
            if (certificateRejected) {
                // Offer the next certificate next time: strict head units only accept Google-issued ones.
                settings.certificateIndex = settings.certificateIndex + 1
                log("head unit rejected the certificate; next connection offers \"${certs.pick(settings.certificateIndex).name}\"")
            }
            log("disconnected: $reason")
            _state.update { it.copy(connected = false, status = "Not connected", video = "", lastError = reason.takeIf { r -> "stopped" !in r && "ended" !in r }) }
            updateNotification("Not connected")
            // A USB link that dropped while still plugged in usually comes back: retry a few times.
            val acc = usbAccessory
            if (acc != null && usbRetries++ < 3 && getSystemService(UsbManager::class.java).accessoryList?.contains(acc) == true) {
                main.postDelayed({ if (this@ProjectionService.session == null) openUsb(acc) }, 1_500)
            } else {
                usbAccessory = null
                usbRetries = 0
                main.postDelayed({ if (idle()) stopEverything() }, 3_000)
            }
        }
    }

    // ------------------------------------------------------------------ audio capture consent

    private fun acceptAudioConsent(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) ?: return
        // The service must be a media-projection foreground service before taking the token.
        goForeground(mediaProjection = true)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection?.stop()
        projection = runCatching { mpm.getMediaProjection(code, data) }.onFailure { log("audio capture: ${it.message}") }.getOrNull()
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                _state.update { it.copy(audioCapture = false) }
            }
        }, main)
        _state.update { it.copy(audioCapture = projection != null) }
        log(if (projection != null) "car audio enabled" else "car audio not enabled")
    }

    // ------------------------------------------------------------------ foreground, notification

    private fun goForeground(mediaProjection: Boolean = projection != null) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (mediaProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        runCatching { startForeground(NOTIFICATION_ID, notification(_state.value.status), types) }
            .onFailure { Log.w(TAG, "startForeground", it) }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car connection", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProjectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aare:projection").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun idle() = session == null && tcpServer == null && wirelessJob?.isActive != true

    private fun stopEverything() {
        session?.stop()
        tcpServer?.stop(); tcpServer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$stamp  $line").takeLast(200)) }
    }

    companion object {
        private const val TAG = "AareService"
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_USB = "net.prototux.aare.USB"
        const val ACTION_IDLE = "net.prototux.aare.IDLE"
        const val ACTION_STOP = "net.prototux.aare.STOP"
        const val ACTION_WIRELESS = "net.prototux.aare.WIRELESS"
        const val EXTRA_DEVICE = "device"
        const val ACTION_AUDIO_CONSENT = "net.prototux.aare.AUDIO_CONSENT"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        fun startUsb(context: Context, accessory: UsbAccessory) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_USB)
                .putExtra(UsbManager.EXTRA_ACCESSORY, accessory))
        }

        /** (Re)applies the listening settings: starts the service if something should listen. */
        fun startIdleIfEnabled(context: Context) {
            val s = Settings(context)
            if (s.headUnitServer || _state.value.running) {
                context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_IDLE))
            }
        }

        /**
         * A wireless head unit appeared. Starting a foreground service from the background can be
         * refused; then a notification lets the driver start it with one tap.
         */
        fun startWireless(context: Context, address: String) {
            val intent = Intent(context, ProjectionService::class.java).setAction(ACTION_WIRELESS).putExtra(EXTRA_DEVICE, address)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "can't start from background, asking the user", e)
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car connection", NotificationManager.IMPORTANCE_LOW))
                val pi = PendingIntent.getForegroundService(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(2, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Car nearby").setContentText("Tap to connect wirelessly").setContentIntent(pi).setAutoCancel(true).build())
            }
        }

        fun giveAudioConsent(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_AUDIO_CONSENT)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
