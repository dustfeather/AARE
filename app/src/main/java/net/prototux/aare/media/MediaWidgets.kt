package net.prototux.aare.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.ui.theme.LocalCarDimens

/** Artwork for a browse item, loaded off the main thread; null until (or unless) it loads. */
@Composable
internal fun rememberArt(loader: ArtLoader, uri: Uri?, data: ByteArray?, maxPx: Int): Bitmap? {
    val key = ArtLoader.artKey(uri, data, maxPx)
    val bmp by produceState(loader.cached(key), key) {
        value = loader.cached(key)
        if (value == null && key != null) value = loader.art(uri, data, maxPx)
    }
    return bmp
}

@Composable
internal fun rememberIcon(loader: ArtLoader, ref: IconRef?, sizePx: Int): Bitmap? {
    val bmp by produceState<Bitmap?>(null, ref) {
        value = ref?.let { loader.icon(it, sizePx) }
    }
    return bmp
}

/** A rounded square of artwork, or a tinted placeholder icon. */
@Composable
internal fun ArtBox(art: Bitmap?, placeholder: ImageVector, modifier: Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    if (art != null) {
        Image(art.asImageBitmap(), null, modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(placeholder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}

/** A circular icon button with a car-size touch target; [filled] for the primary action. */
@Composable
internal fun CircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    enabled: Boolean = true,
    size: Dp = LocalCarDimens.current.touchTarget,
) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(size * 0.47f))
    }
}

/** A pill with an optional icon: tabs, "Retry", error actions. */
@Composable
internal fun Pill(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** An error the app reported, with its fix-it action and/or a retry. */
@Composable
internal fun ErrorCard(error: MediaError, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val r = LocalCarDimens.current.cardRadius
    Row(
        modifier.clip(RoundedCornerShape(r)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(16.dp))
        Text(error.message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (error.action != null) {
            Spacer(Modifier.width(12.dp))
            // the app's own resolution (e.g. its sign-in screen) opens on the phone
            Pill(error.actionLabel ?: "Fix", selected = true, onClick = { runCatching { error.action.send() } })
        }
        if (onRetry != null) {
            Spacer(Modifier.width(12.dp))
            Pill("Retry", selected = error.action == null, onClick = onRetry)
        }
    }
}

/** Centered spinner or message for empty states. */
@Composable
internal fun Placeholder(text: String, loading: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(56.dp))
            icon != null -> Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "1:05", "12:34", "1:02:03". */
internal fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Fills the width with a header row: used by section titles in lists and grids. */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
