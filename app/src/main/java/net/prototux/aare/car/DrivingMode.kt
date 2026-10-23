package net.prototux.aare.car

import android.app.NotificationManager
import android.content.Context
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.notify.CarNotificationListener

/**
 * What changes on the phone while it drives the car, like Android Auto's driving settings: Do Not
 * Disturb (calls and priority conversations still come through), and an automatic reply to people
 * who write, once per conversation per drive.
 */
class DrivingMode(context: Context, private val settings: Settings) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var previousFilter: Int? = null
    private val replied = HashSet<String>()

    fun start() {
        if (!settings.dndWhileDriving || !nm.isNotificationPolicyAccessGranted) return
        val current = nm.currentInterruptionFilter
        // Already quieter than "priority only": leave it.
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return
        previousFilter = current
        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            .onSuccess { DiagLog.add("driving: Do Not Disturb on") }
    }

    fun stop() {
        previousFilter?.let { f -> runCatching { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(f) } }
        previousFilter = null
        replied.clear()
    }

    /** A message arrived (notification key [key]); answers it if auto-reply is on. Main thread. */
    fun onMessage(key: String, group: Boolean) {
        if (!settings.autoReply || group) return
        val listener = CarNotificationListener.instance ?: return
        val c = listener.conversations().firstOrNull { it.key == key } ?: return
        if (!c.canReply || !replied.add(c.packageName + "/" + c.title)) return
        if (listener.reply(c, settings.autoReplyText)) DiagLog.add("driving: auto-replied to ${c.title}")
    }

    companion object {
        fun accessGranted(context: Context) = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }
}
