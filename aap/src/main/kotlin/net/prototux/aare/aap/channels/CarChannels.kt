package net.prototux.aare.aap.channels

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.bluetooth.message.BluetoothPairingMethodOuterClass.BluetoothPairingMethod
import aap_protobuf.service.bluetooth.message.BluetoothPairingRequestOuterClass.BluetoothPairingRequest
import aap_protobuf.service.bluetooth.message.BluetoothPairingResponseOuterClass.BluetoothPairingResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.sink.message.KeyBindingRequestOuterClass.KeyBindingRequest
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import aap_protobuf.service.navigationstatus.message.NavigationCurrentPositionOuterClass.NavigationCurrentPosition
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnDistanceEventOuterClass.NavigationNextTurnDistanceEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.BluetoothMsg
import net.prototux.aare.aap.InputMsg
import net.prototux.aare.aap.MediaPlaybackMsg
import net.prototux.aare.aap.NavStatusMsg
import net.prototux.aare.aap.Priority
import net.prototux.aare.aap.SensorMsg
import net.prototux.aare.aap.ServiceChannel

/** Touch screen, touch pad, buttons and rotary controller of the car. */
class InputChannel(service: Service, private val listener: Listener) : ServiceChannel(service, "input") {
    interface Listener {
        /** Called on the session's reader thread. */
        fun onInput(report: InputReport)
    }

    val keycodes: List<Int> = service.inputSourceService.keycodesSupportedList
    val touchScreen = service.inputSourceService.touchscreenList.firstOrNull()
    val touchPad = service.inputSourceService.touchpadList.firstOrNull()

    override fun onOpened() {
        // Bind every key the car has: we decide later what each one does.
        send(InputMsg.KEY_BINDING_REQUEST, KeyBindingRequest.newBuilder().addAllKeycodes(keycodes).build())
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            InputMsg.INPUT_REPORT -> listener.onInput(InputReport.parseFrom(msg.bodyBytes()))
            InputMsg.KEY_BINDING_RESPONSE -> Unit
        }
    }
}

/** Car sensors: driving status, night mode, GPS, speed, gear... */
class SensorChannel(service: Service, private val listener: Listener) : ServiceChannel(service, "sensors") {
    interface Listener {
        fun onSensors(batch: SensorBatch)
    }

    val available: List<SensorType> = service.sensorSourceService.sensorsList.map { it.sensorType }

    override fun onOpened() {
        for (type in WANTED) {
            if (type in available) {
                send(SensorMsg.REQUEST, SensorRequest.newBuilder().setType(type).setMinUpdatePeriod(0).build())
            }
        }
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            SensorMsg.BATCH -> listener.onSensors(SensorBatch.parseFrom(msg.bodyBytes()))
            // A refused sensor just stays silent.
            SensorMsg.RESPONSE, SensorMsg.ERROR -> Unit
        }
    }

    companion object {
        val WANTED = listOf(
            SensorType.SENSOR_DRIVING_STATUS_DATA,
            SensorType.SENSOR_NIGHT_MODE,
            SensorType.SENSOR_LOCATION,
            SensorType.SENSOR_SPEED,
            SensorType.SENSOR_GEAR,
            SensorType.SENSOR_PARKING_BRAKE,
            SensorType.SENSOR_COMPASS,
            SensorType.SENSOR_FUEL,
        )
    }
}

/**
 * Turn-by-turn for the instrument cluster / head-unit widgets. Newer head units read
 * NavigationState + CurrentPosition; older ones only the (deprecated) turn and distance events,
 * so we send both.
 */
class NavigationStatusChannel(service: Service) : ServiceChannel(service, "navstatus") {
    val wantsImages = service.navigationStatusService.type ==
        aap_protobuf.service.navigationstatus.NavigationStatusServiceOuterClass.NavigationStatusService.InstrumentClusterType.IMAGE
    val imageOptions = service.navigationStatusService.imageOptions
    val minIntervalMs = service.navigationStatusService.minimumIntervalMs
    @Volatile var active = false
        private set
    private var lastSentMs = 0L

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            NavStatusMsg.START -> active = true
            NavStatusMsg.STOP -> active = false
        }
    }

    fun status(s: NavigationStatus.NavigationStatusEnum) =
        send(NavStatusMsg.STATUS, NavigationStatus.newBuilder().setStatus(s).build(), Priority.INPUT)

    /** Sends the current step; rate-limited to what the head unit asked for. */
    fun update(
        state: NavigationState?, position: NavigationCurrentPosition?,
        turn: NavigationNextTurnEvent?, distance: NavigationNextTurnDistanceEvent?, force: Boolean = false,
    ) {
        val now = System.nanoTime() / 1_000_000
        if (!force && now - lastSentMs < minIntervalMs) return
        lastSentMs = now
        state?.let { send(NavStatusMsg.STATE, it, Priority.INPUT) }
        position?.let { send(NavStatusMsg.CURRENT_POSITION, it, Priority.INPUT) }
        turn?.let { send(NavStatusMsg.TURN_EVENT, it, Priority.INPUT) }
        distance?.let { send(NavStatusMsg.DISTANCE_EVENT, it, Priority.INPUT) }
    }
}

/** Now-playing info for the head unit's own widgets and cluster. */
class MediaPlaybackChannel(service: Service) : ServiceChannel(service, "playback") {
    override fun onMessage(msg: AapMessage) = Unit

    fun status(s: MediaPlaybackStatus) = send(MediaPlaybackMsg.STATUS, s, Priority.INPUT)
    fun metadata(m: MediaPlaybackMetadata) = send(MediaPlaybackMsg.METADATA, m, Priority.AUDIO)
}

/**
 * Bluetooth: the head unit gives its address; the phone asks it to pair so calls go through the
 * car (HFP). Android hides the phone's own address from normal apps, so [phoneAddress] may be
 * unknown, in which case we just let the user pair the usual way.
 */
class BluetoothChannel(service: Service, private val listener: Listener) : ServiceChannel(service, "bluetooth") {
    interface Listener {
        fun onPairingResponse(alreadyPaired: Boolean, ok: Boolean) {}
    }

    val carAddress: String = service.bluetoothService.carAddress
    val methods: List<BluetoothPairingMethod> = service.bluetoothService.supportedPairingMethodsList

    fun requestPairing(phoneAddress: String) {
        val method = when {
            BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON in methods -> BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN in methods -> BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN
            methods.isNotEmpty() -> methods.first()
            else -> BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON
        }
        send(BluetoothMsg.PAIRING_REQUEST, BluetoothPairingRequest.newBuilder().setPhoneAddress(phoneAddress).setPairingMethod(method).build())
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            BluetoothMsg.PAIRING_RESPONSE -> {
                val r = BluetoothPairingResponse.parseFrom(msg.bodyBytes())
                listener.onPairingResponse(r.alreadyPaired, r.status.number == 0)
            }
        }
    }
}
