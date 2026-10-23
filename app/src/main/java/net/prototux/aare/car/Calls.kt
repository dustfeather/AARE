package net.prototux.aare.car

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Phone calls from the car through Android's telephony, so their sound goes through the car's
 * Bluetooth hands-free link as with Android Auto. Needs CALL_PHONE, ANSWER_PHONE_CALLS and
 * READ_PHONE_STATE; every call fails quietly without them.
 */
@SuppressLint("MissingPermission")
object Calls {
    fun place(context: Context, number: String) {
        runCatching { context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle()) }
    }

    fun answer(context: Context) {
        runCatching { context.getSystemService(TelecomManager::class.java).acceptRingingCall() }
    }

    @Suppress("DEPRECATION") // still the way to hang up (or decline) without being the dialer
    fun end(context: Context) {
        runCatching { context.getSystemService(TelecomManager::class.java).endCall() }
    }

    /** TelephonyManager.CALL_STATE_IDLE, _RINGING or _OFFHOOK. */
    @Suppress("DEPRECATION")
    fun state(context: Context): Int = runCatching { context.getSystemService(TelephonyManager::class.java).callState }
        .getOrDefault(TelephonyManager.CALL_STATE_IDLE)
}
