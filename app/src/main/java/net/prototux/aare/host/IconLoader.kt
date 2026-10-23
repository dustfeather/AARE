package net.prototux.aare.host

import android.content.Context
import android.util.LruCache
import androidx.car.app.model.CarIcon
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Loads car app icons. Resource icons live in the app's own package, so they're resolved
 * against its resources; URI icons rely on the app granting us read access (it does so for
 * the host package it handshook with).
 */
class IconLoader(private val context: Context) {
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun load(icon: CarIcon, sizePx: Int): ImageBitmap? {
        val ic = icon.icon ?: return null
        val key = when (ic.type) {
            IconCompat.TYPE_RESOURCE -> "res:${ic.resPackage}:${ic.resId}:$sizePx"
            IconCompat.TYPE_URI, IconCompat.TYPE_URI_ADAPTIVE_BITMAP -> "uri:${ic.uri}:$sizePx"
            else -> "obj:${System.identityHashCode(ic)}:$sizePx"
        }
        cache.get(key)?.let { return it }
        val drawable = runCatching {
            if (ic.type == IconCompat.TYPE_RESOURCE) {
                val pkgContext = context.createPackageContext(ic.resPackage, 0)
                androidx.core.content.res.ResourcesCompat.getDrawable(pkgContext.resources, ic.resId, pkgContext.theme)
            } else {
                ic.loadDrawable(context)
            }
        }.getOrNull() ?: return null
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else sizePx
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else sizePx
        val scale = sizePx.toFloat() / maxOf(w, h)
        val bmp = runCatching {
            drawable.toBitmap(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt())).asImageBitmap()
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }
}
