package net.prototux.aare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.prototux.aare.Settings
import net.prototux.aare.transport.wireless.WirelessAutoStarter

/**
 * A paired head unit offering wireless Android Auto just connected over Bluetooth (or its
 * services were refreshed): start the projection service, which runs the Wi-Fi bootstrap.
 */
class WirelessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings(context).wireless) return
        val device = WirelessAutoStarter.headUnitIn(intent) ?: return
        ProjectionService.startWireless(context, device.address)
    }
}
