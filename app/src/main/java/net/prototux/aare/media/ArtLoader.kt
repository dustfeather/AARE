package net.prototux.aare.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads artwork and icons off the main thread, downscaled, with a small memory cache.
 *
 * Software bitmaps on purpose: art may be re-encoded for the head unit's media status, and
 * hardware bitmaps cannot be read back.
 */
internal class ArtLoader(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(key: String?): Bitmap? = key?.let { cache.get(it) }

    /** Artwork from embedded bytes (preferred) or a content/file/resource/http(s) URI. */
    suspend fun art(uri: Uri?, data: ByteArray?, maxPx: Int = MediaKeys.ART_SIZE_PX): Bitmap? {
        val key = artKey(uri, data, maxPx) ?: return null
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    // Android 8: no ImageDecoder.
                    val bytes = data ?: if (uri!!.scheme == "http" || uri.scheme == "https") download(uri)
                        else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes?.let { decodeLegacy(it, maxPx) }
                } else when {
                    data != null -> decode(ImageDecoder.createSource(ByteBuffer.wrap(data)), maxPx)
                    uri!!.scheme == "http" || uri.scheme == "https" ->
                        download(uri)?.let { decode(ImageDecoder.createSource(ByteBuffer.wrap(it)), maxPx) }
                    else -> decode(ImageDecoder.createSource(context.contentResolver, uri), maxPx)
                }
            } catch (e: Exception) {
                // other apps' content URIs often need a grant we do not have; no art is fine
                Log.d(TAG, "no art for $uri: $e")
                null
            }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /** A (usually monochrome) icon: a drawable of another package, ours, or a URI. */
    suspend fun icon(ref: IconRef, sizePx: Int): Bitmap? {
        if (ref.resId == 0) return ref.uri?.let { art(it, null, sizePx) }
        val key = "i:${ref.packageName}:${ref.resId}:$sizePx"
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            try {
                val res = if (ref.packageName == null) context.resources
                else context.packageManager.getResourcesForApplication(ref.packageName)
                ResourcesCompat.getDrawable(res, ref.resId, null)?.toBitmap(sizePx, sizePx)
            } catch (e: Exception) {
                Log.d(TAG, "no icon $ref: $e")
                null
            }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /** BitmapFactory, scaled down by powers of two then to [maxPx]. */
    private fun decodeLegacy(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxPx.toFloat() / max(bmp.width, bmp.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true) else bmp
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decode(source: ImageDecoder.Source, maxPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxPx.toFloat() / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }

    private fun download(uri: Uri): ByteArray? {
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DOWNLOAD) return null
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "AareArt"
        private const val CACHE_BYTES = 32 * 1024 * 1024
        private const val MAX_DOWNLOAD = 10 * 1024 * 1024

        fun artKey(uri: Uri?, data: ByteArray?, maxPx: Int): String? = when {
            data != null -> "d:${data.size}:${data.contentHashCode()}:$maxPx"
            uri != null && uri.scheme in SCHEMES -> "u:$uri:$maxPx"
            else -> null
        }

        private val SCHEMES = setOf(
            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_ANDROID_RESOURCE, "http", "https",
        )
    }
}
