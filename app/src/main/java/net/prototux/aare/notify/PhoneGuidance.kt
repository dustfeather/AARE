package net.prototux.aare.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toBitmap
import java.io.FileNotFoundException
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import net.prototux.aare.media.ArtLoader
import org.lsposed.hiddenapibypass.HiddenApiBypass

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
            // The icon and the layout can point at a URI, which we'd open as AARE: only let them
            // reach the posting app's own content. From Q a scoped resolver does it (an icon's file
            // URI skips the resolver: refused too); before Q, iconAllowed below and LegacyLayoutLinks.
            val scoped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PosterContext(context, sbn.packageName) else null
            val icon = n.getLargeIcon()?.takeIf {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || it.type != Icon.TYPE_URI && it.type != Icon.TYPE_URI_ADAPTIVE_BITMAP ||
                    it.uri.scheme == ContentResolver.SCHEME_CONTENT
            }
            var arrow: Bitmap? = icon?.let {
                runCatching {
                    require(ArtLoader.iconAllowed(context, it, sbn.packageName))
                    it.loadDrawable(scoped ?: context)?.toBitmap(192, 192)
                }.getOrNull()
            }
            // Google Maps and others draw guidance in a custom layout: read its text and image.
            val layout = n.bigContentView ?: n.contentView
            // Android 8 and 9 can't scope the layout's resolver: check its links before inflating instead.
            val layoutContext = scoped ?: context.takeIf { layout != null && LegacyLayoutLinks.allowed(context, layout, sbn.packageName) }
            if (lines.size < 2 && layout != null && layoutContext != null) {
                val views = runCatching { inflate(layoutContext, layout) }.getOrNull()
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
        private fun inflate(context: Context, rv: RemoteViews): Pair<List<String>, Bitmap?> {
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

/**
 * [base] with a ContentResolver that opens only content URIs provided by [pkg]: an icon or a
 * layout image in its notification can't make us read, with our permissions, what only AARE may
 * (a contact photo, say). Files and plain paths don't go through the resolver; they only reach
 * AARE's own files, as we hold no storage permission.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class PosterContext(base: Context, private val pkg: String) : ContextWrapper(base) {
    private val resolver = ContentResolver.wrap(object : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? =
            base.contentResolver.openAssetFileDescriptor(owned(uri, mode), mode, signal)

        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor? =
            base.contentResolver.openTypedAssetFileDescriptor(owned(uri, "r"), mimeTypeFilter, opts, signal)
    })

    override fun getContentResolver(): ContentResolver = resolver

    private fun owned(uri: Uri, mode: String): Uri {
        val authority = uri.authority?.takeIf { uri.scheme == ContentResolver.SCHEME_CONTENT && mode == "r" }
        val owner = authority?.let {
            if (Build.VERSION.SDK_INT >= 33) packageManager.resolveContentProvider(it, PackageManager.ComponentInfoFlags.of(0))
            else @Suppress("DEPRECATION") packageManager.resolveContentProvider(it, 0)
        }?.packageName
        if (owner != pkg) throw FileNotFoundException("$pkg can't show $uri")
        return uri
    }
}

/**
 * Android 8 and 9, where a layout can't get [PosterContext]'s resolver: whether every image link in
 * [RemoteViews] is the posting app's own content, checked before inflating, as [RemoteViews.apply]
 * loads them with our identity. The links sit in RemoteViews' private actions, read by reflection,
 * which these versions allow (on 9 once the hidden API checks are lifted for RemoteViews: otherwise
 * getDeclaredFields silently leaves restricted fields out, and their links would go unchecked).
 * Every field of every action is walked, so no action type is missed; anything that can't be read
 * counts as not allowed, and the guidance falls back to the notification's text.
 */
private object LegacyLayoutLinks {
    private const val TAG = "AareGuidance"
    private const val MAX_DEPTH = 32

    private val unrestricted by lazy {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P || HiddenApiBypass.addHiddenApiExemptions("Landroid/widget/RemoteViews")
    }

    // Private on purpose: only on Android 8 and 9, whose RemoteViews won't change again.
    @SuppressLint("DiscouragedPrivateApi")
    fun allowed(context: Context, rv: RemoteViews, pkg: String): Boolean = try {
        check(unrestricted) { "hidden API checks still on" }
        // The actions' field must be visible, or a walk that doesn't see it would pass anything.
        RemoteViews::class.java.getDeclaredField("mActions")
        Walk(context, pkg).ok(rv, 0).also { if (!it) Log.d(TAG, "$pkg's layout links outside its own content: not inflated") }
    } catch (e: Throwable) {
        Log.d(TAG, "can't check the links in $pkg's layout, not inflating it", e)
        false
    }

    private class Walk(private val context: Context, private val pkg: String) {
        private val seen = IdentityHashMap<Any, Unit>()

        fun ok(value: Any?, depth: Int): Boolean {
            if (value == null || seen.put(value, Unit) != null) return true
            check(depth < MAX_DEPTH) { "layout nested too deep" }
            return when (value) {
                is Uri -> ArtLoader.allowed(context, value, pkg)
                is Icon -> ArtLoader.iconAllowed(context, value, pkg)
                is Collection<*> -> value.all { ok(it, depth + 1) }
                is Map<*, *> -> value.values.all { ok(it, depth + 1) }
                is Array<*> -> value.all { ok(it, depth + 1) }
                // The layout, its landscape/portrait variants, nested layouts and all action types.
                is RemoteViews -> fields(value, depth)
                else -> !value.javaClass.name.startsWith("android.widget.RemoteViews$") || fields(value, depth)
            }
        }

        private fun fields(obj: Any, depth: Int): Boolean {
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                    f.isAccessible = true
                    if (!ok(f.get(obj), depth + 1)) return false
                }
                c = c.superclass
            }
            return true
        }
    }
}
