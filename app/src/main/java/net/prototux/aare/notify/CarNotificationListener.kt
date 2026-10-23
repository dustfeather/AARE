package net.prototux.aare.notify

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale

/**
 * Messages in the car. Conversations posted as MessagingStyle notifications (what Android Auto
 * reads too) appear as banners on the car screen, with "Read aloud" and "Mark as read".
 *
 * Also what lets the media hub see other apps' media sessions (notification access).
 */
class CarNotificationListener : NotificationListenerService() {
    private var tts: TextToSpeech? = null

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sink = sink ?: return
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return
        val last = style.messages.lastOrNull() ?: return
        val sender = last.person?.name?.toString() ?: style.conversationTitle?.toString() ?: ""
        val text = last.text?.toString() ?: return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val icon: Bitmap? = (n.getLargeIcon() ?: n.smallIcon)?.let { loadIcon(it) }
        val markRead = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
        val actions = buildList<Pair<String, () -> Unit>> {
            add("Read aloud" to { speak("$sender. $text") })
            markRead?.let { pi -> add("Mark read" to { runCatching { pi.send() } }) }
        }
        sink(Message(sbn.key, if (style.isGroupConversation) "${style.conversationTitle} · $sender" else sender.ifEmpty { app }, text, icon, actions))
    }

    private fun actionWith(n: Notification, semantic: Int): PendingIntent? {
        val count = NotificationCompat.getActionCount(n)
        for (i in 0 until count) {
            val a = NotificationCompat.getAction(n, i) ?: continue
            if (a.semanticAction == semantic) return a.actionIntent
        }
        return null
    }

    private fun loadIcon(icon: Icon): Bitmap? = runCatching { icon.loadDrawable(this)?.toBitmap(96, 96) }.getOrNull()

    /** Speaks with the media usage, so it reaches the car along with the music. */
    private fun speak(text: String) {
        val t = tts
        if (t != null) { t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), null); return }
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "no text-to-speech"); return@TextToSpeech }
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts?.language = Locale.getDefault()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), null)
        }
    }

    /** A conversation update for the car screen. */
    class Message(val key: String, val title: String, val text: String, val icon: Bitmap?, val actions: List<Pair<String, () -> Unit>>)

    companion object {
        private const val TAG = "AareNotify"
        @Volatile var instance: CarNotificationListener? = null
            private set

        /** Set by the car session while connected; called on the main thread. */
        @Volatile var sink: ((Message) -> Unit)? = null
    }
}
