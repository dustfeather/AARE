package net.prototux.aare.notify

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import net.prototux.aare.media.ArtLoader

/**
 * Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…), which can't be
 * hosted on the car screen. Read from its ongoing navigation notification, so it is only what
 * that notification shows: the lines of text and the turn arrow.
 */
class PhoneGuidance(
    val key: String,
    val packageName: String,
    val appLabel: String,
    /** The biggest line: usually the distance to the turn, or the instruction. */
    val primary: String,
    val secondary: String,
    /** Usually the arrival time and the remaining distance. */
    val detail: String,
    /** The app's turn arrow. */
    val arrow: Bitmap?,
    /** Distance to the next turn, if one of the lines starts with it. */
    val distance: Distance?,
    /** The instruction without the distance: what the cluster shows as the road. */
    val instruction: String,
) {
    class Distance(val meters: Int, val display: String, val unit: DistanceUnit)
    enum class DistanceUnit { METERS, KILOMETERS, MILES, FEET, YARDS }

    companion object {
        private val DISTANCE = Regex("""(?<![\w.,])(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)

        /** Whether [sbn] is a navigation app's guidance notification. */
        fun isGuidance(sbn: StatusBarNotification): Boolean {
            val n = sbn.notification
            return n.category == Notification.CATEGORY_NAVIGATION && n.flags and Notification.FLAG_ONGOING_EVENT != 0
        }

        /** Reads [sbn]; main thread (it may inflate the notification's custom layout). */
        fun from(context: Context, sbn: StatusBarNotification): PhoneGuidance? {
            val n = sbn.notification
            val extras = n.extras
            var lines = listOfNotNull(
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_TEXT),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT),
            ).map { it.toString().trim() }.filter { it.isNotEmpty() }
            var arrow: Bitmap? = n.getLargeIcon()?.let {
                runCatching {
                    require(ArtLoader.iconAllowed(context, it, sbn.packageName))
                    it.loadDrawable(context)?.toBitmap(192, 192)
                }.getOrNull()
            }
            // Google Maps and others draw guidance in a custom layout: read its text and image.
            if (lines.size < 2) {
                val views = runCatching { inflate(context, n) }.getOrNull()
                if (views != null) {
                    val (texts, image) = views
                    if (texts.size > lines.size) lines = texts
                    if (arrow == null) arrow = image
                }
            }
            if (lines.isEmpty()) return null
            val pm = context.packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            // The step distance is in the first two lines; the third is usually the trip's.
            val distance = lines.take(2).firstNotNullOfOrNull(::distanceIn)
            val instruction = lines.take(2)
                .map { DISTANCE.replace(it, "").trim(' ', '-', '·', ',') }
                .maxByOrNull { it.length }.orEmpty()
            return PhoneGuidance(
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = label,
                primary = lines[0],
                secondary = lines.getOrNull(1).orEmpty(),
                detail = lines.drop(2).joinToString(" · "),
                arrow = arrow,
                distance = distance,
                instruction = instruction,
            )
        }

        internal fun distanceIn(text: String): Distance? {
            val m = DISTANCE.find(text) ?: return null
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = when (m.groupValues[2].lowercase()) {
                "km" -> DistanceUnit.KILOMETERS
                "mi" -> DistanceUnit.MILES
                "ft" -> DistanceUnit.FEET
                "yd" -> DistanceUnit.YARDS
                else -> DistanceUnit.METERS
            }
            val meters = value * when (unit) {
                DistanceUnit.KILOMETERS -> 1000.0
                DistanceUnit.MILES -> 1609.344
                DistanceUnit.FEET -> 0.3048
                DistanceUnit.YARDS -> 0.9144
                DistanceUnit.METERS -> 1.0
            }
            return Distance(meters.toInt(), m.groupValues[1].replace(',', '.'), unit)
        }

        /** The visible text lines (in layout order) and the largest image of the notification's layout. */
        private fun inflate(context: Context, n: Notification): Pair<List<String>, Bitmap?>? {
            val rv = n.bigContentView ?: n.contentView ?: return null
            val root = rv.apply(context, FrameLayout(context))
            val texts = ArrayList<String>()
            var image: Bitmap? = null
            var imageArea = 0
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                when (v) {
                    is TextView -> v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    is ImageView -> {
                        val d = v.drawable ?: return
                        val area = d.intrinsicWidth * d.intrinsicHeight
                        if (area > imageArea) {
                            imageArea = area
                            image = (d as? BitmapDrawable)?.bitmap ?: runCatching { d.toBitmap(192, 192) }.getOrNull()
                        }
                    }
                    is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(root)
            return texts to image
        }
    }
}
