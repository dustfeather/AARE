package net.prototux.aare.headunit

import aap_protobuf.service.control.message.BatteryStatusNotificationOuterClass.BatteryStatusNotification
import aap_protobuf.service.phonestatus.message.PhoneStatusOuterClass.PhoneStatus

/**
 * What the phone says about itself (calls, signal, battery, whether it can call), as one line for
 * the desktop head unit's side panel. Each part is kept until the next message of its kind.
 */
class PhoneStatusText {
    private var calls = ""
    private var signal = ""
    private var battery = ""

    fun status(body: ByteArray): String {
        val s = runCatching { PhoneStatus.parseFrom(body) }.getOrElse { return "bad phone status: ${it.message}" }
        signal = if (s.hasSignalStrength()) "signal ${s.signalStrength}/4" else ""
        calls = s.callsList.joinToString("; ") { c ->
            val who = listOf(c.callerId, c.callerNumber, c.callerNumberType.takeIf { it.isNotEmpty() }?.let { "($it)" })
                .filter { !it.isNullOrEmpty() }.joinToString(" ").ifEmpty { "unknown caller" }
            val photo = if (c.hasCallerThumbnail()) ", photo ${c.callerThumbnail.size()} B" else ""
            "📞 ${c.phoneState.name.lowercase().replace('_', ' ')}: $who, ${c.callDurationSeconds / 60}:${"%02d".format(c.callDurationSeconds % 60)}$photo"
        }
        return line()
    }

    fun battery(body: ByteArray): String {
        val b = runCatching { BatteryStatusNotification.parseFrom(body) }.getOrElse { return "bad battery status: ${it.message}" }
        battery = "🔋 ${b.batteryLevel}%" + (if (b.criticalBattery) " critical" else "") +
            (if (b.hasTimeRemainingS()) ", ${b.timeRemainingS / 60} min left" else "")
        return line()
    }

    private fun line() = listOf(calls, signal, battery).filter { it.isNotEmpty() }.joinToString("  ·  ")
}
