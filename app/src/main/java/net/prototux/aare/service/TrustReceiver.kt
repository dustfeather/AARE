package net.prototux.aare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The "Trust" action of the "New head unit" notification: trusts the refused certificate, so the
 * next connection to that head unit goes through. Not exported: only AARE's own (immutable)
 * pending intent reaches it.
 */
class TrustReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_PEER_KEY) ?: return
        val sha = intent.getStringExtra(EXTRA_SHA256) ?: return
        ProjectionService.trust(context, key, sha)
    }

    companion object {
        const val NOTIFICATION_ID = 3
        const val EXTRA_PEER_KEY = "peer_key"
        const val EXTRA_SHA256 = "sha256"
    }
}
