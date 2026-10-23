package net.prototux.aare.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.transport.wireless.WirelessAutoStarter

/**
 * A paired head unit offering wireless Android Auto just connected over Bluetooth (or its
 * services were refreshed): start the projection service, which runs the Wi-Fi bootstrap.
 *
 * Android caches a device's services from pairing time, and many cars only offer Android Auto
 * wireless later. So when a paired device connects without it, its services are queried again
 * (as Android Auto does) and the answer is awaited here.
 */
class WirelessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings(context).wireless) return
        WirelessAutoStarter.headUnitIn(intent)?.let { start(context, it); return }
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED && bonded(device)) refreshServices(context, device)
    }

    private fun start(context: Context, device: BluetoothDevice) {
        DiagLog.add("wireless: ${nameOf(device)} offers Android Auto wireless, connecting")
        ProjectionService.startWireless(context, device.address)
    }

    @SuppressLint("MissingPermission") // the broadcast itself needs BLUETOOTH_CONNECT
    private fun refreshServices(context: Context, device: BluetoothDevice) {
        val app = context.applicationContext
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        var done = false
        lateinit var answer: BroadcastReceiver
        fun finish(found: BluetoothDevice?) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(null)
            runCatching { app.unregisterReceiver(answer) }
            if (found != null) start(app, found)
            else DiagLog.add("wireless: ${nameOf(device)} connected over Bluetooth but doesn't offer Android Auto wireless")
            pending.finish()
        }
        answer = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address == device.address) finish(WirelessAutoStarter.headUnitIn(i))
            }
        }
        // Sent by the Bluetooth stack, which is not our uid: exported.
        ContextCompat.registerReceiver(app, answer, IntentFilter(BluetoothDevice.ACTION_UUID), ContextCompat.RECEIVER_EXPORTED)
        main.postDelayed({ finish(null) }, SDP_TIMEOUT_MS)
        if (!runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)) finish(null)
    }

    @SuppressLint("MissingPermission")
    private fun bonded(device: BluetoothDevice) = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun nameOf(device: BluetoothDevice) = runCatching { device.name }.getOrNull() ?: device.address

    private companion object {
        /** A receiver may run for about 10 s; SDP answers in a few. */
        const val SDP_TIMEOUT_MS = 8_000L
    }
}
