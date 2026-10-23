package net.prototux.aare.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** One conversation, from a messaging app's MessagingStyle notification. */
class Conversation(
    /** The notification's key. */
    val key: String,
    val packageName: String,
    /** The contact or group name. */
    val title: String,
    /** Oldest first. */
    val messages: List<Line>,
    val icon: Bitmap?,
    val timeMs: Long,
    val markRead: PendingIntent?,
    /** The reply action, with its RemoteInput. */
    val reply: NotificationCompat.Action?,
) {
    class Line(val sender: String, val text: String, val timeMs: Long)

    val canReply: Boolean get() = reply?.remoteInputs?.isNotEmpty() == true

    /** What is read aloud: the latest messages, each with its sender. */
    fun spoken(): String = messages.takeLast(5).joinToString(". ") { if (it.sender.isNotEmpty() && it.sender != title) "${it.sender}: ${it.text}" else it.text }
        .let { "$title. $it" }
}

/**
 * Messages in the car. Conversations posted as MessagingStyle notifications (what Android Auto
 * reads too) appear as banners on the car screen, with "Read aloud" and "Mark as read", and in
 * each messaging app's screen (see MessagingScreen). Navigation apps' guidance notifications
 * become [guidance], for the dashboard and the instrument cluster.
 *
 * Also what lets the media hub see other apps' media sessions (notification access).
 */
class CarNotificationListener : NotificationListenerService() {
    private var tts: TextToSpeech? = null

    override fun onListenerConnected() {
        instance = this
        _changes.update { it + 1 }
        refreshGuidance()
    }

    override fun onListenerDisconnected() {
        instance = null
        _guidance.value = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        _changes.update { it + 1 }
        if (sbn.key == _guidance.value?.key) refreshGuidance()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        _changes.update { it + 1 }
        if (sbn.packageName != packageName && PhoneGuidance.isGuidance(sbn)) {
            PhoneGuidance.from(this, sbn)?.let { _guidance.value = it }
            return
        }
        val sink = sink ?: return
        val c = parse(sbn) ?: return
        val last = c.messages.last()
        val actions = buildList<Pair<String, () -> Unit>> {
            add("Read aloud" to { speak(if (last.sender.isNotEmpty()) "${last.sender}. ${last.text}" else last.text) })
            c.markRead?.let { pi -> add("Mark read" to { runCatching { pi.send() } }) }
        }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val title = if (last.sender.isNotEmpty() && last.sender != c.title) "${c.title} · ${last.sender}" else c.title.ifEmpty { app }
        val group = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.isGroupConversation == true
        sink(Message(sbn.key, title, last.text, c.icon, actions, group) { speak(if (last.sender.isNotEmpty()) "${last.sender}. ${last.text}" else last.text) })
    }

    /** The conversations currently in the notification shade, newest first; all apps if [packageName] is null. */
    fun conversations(packageName: String? = null): List<Conversation> =
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { packageName == null || it.packageName == packageName }
            .mapNotNull(::parse)
            .sortedByDescending { it.timeMs }

    fun readAloud(c: Conversation) = speak(c.spoken())

    /** The newest guidance notification still showing, if any. */
    private fun refreshGuidance() {
        _guidance.value = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != packageName && PhoneGuidance.isGuidance(it) }
            .maxByOrNull { it.postTime }
            ?.let { PhoneGuidance.from(this, it) }
    }

    /** Sends [text] through the conversation's reply action. False if it has none or it failed. */
    fun reply(c: Conversation, text: String): Boolean {
        val action = c.reply ?: return false
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        return runCatching { action.actionIntent?.send(this, 0, intent) }.isSuccess
    }

    private fun parse(sbn: StatusBarNotification): Conversation? {
        val n = sbn.notification
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return null
        val lines = style.messages.mapNotNull { m ->
            val text = m.text?.toString() ?: return@mapNotNull null
            Conversation.Line(m.person?.name?.toString() ?: "", text, m.timestamp)
        }
        if (lines.isEmpty()) return null
        val title = style.conversationTitle?.toString()
            ?: lines.lastOrNull { it.sender.isNotEmpty() }?.sender
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        return Conversation(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            messages = lines,
            icon = (n.getLargeIcon() ?: n.smallIcon)?.let { loadIcon(it) },
            timeMs = lines.last().timeMs.takeIf { it > 0 } ?: sbn.postTime,
            markRead = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)?.actionIntent,
            reply = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                ?: (0 until NotificationCompat.getActionCount(n)).asSequence().mapNotNull { NotificationCompat.getAction(n, it) }
                    .firstOrNull { !it.remoteInputs.isNullOrEmpty() },
        )
    }

    private fun actionWith(n: Notification, semantic: Int): NotificationCompat.Action? {
        val count = NotificationCompat.getActionCount(n)
        for (i in 0 until count) {
            val a = NotificationCompat.getAction(n, i) ?: continue
            if (a.semanticAction == semantic) return a
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
    class Message(
        val key: String,
        val title: String,
        val text: String,
        val icon: Bitmap?,
        val actions: List<Pair<String, () -> Unit>>,
        val group: Boolean,
        /** Reads the message aloud (through the media stream, so it reaches the car). */
        val readAloud: () -> Unit,
    )

    companion object {
        private const val TAG = "AareNotify"
        @Volatile var instance: CarNotificationListener? = null
            private set

        /** Set by the car session while connected; called on the main thread. */
        @Volatile var sink: ((Message) -> Unit)? = null

        private val _guidance = MutableStateFlow<PhoneGuidance?>(null)
        /** Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…). */
        val guidance: StateFlow<PhoneGuidance?> = _guidance

        private val _changes = MutableStateFlow(0)
        /** Bumped whenever a notification is posted or removed: the cue to re-read [conversations]. */
        val changes: StateFlow<Int> = _changes
    }
}
