package net.prototux.aare.host.templates

import android.util.Log
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.OnClickDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.prototux.aare.ui.theme.LocalCarDimens

private const val TAG = "AareTemplates"

/** The host of the template being rendered (provided by [RenderTemplate]). */
internal val LocalTemplateHost = staticCompositionLocalOf<TemplateHost> { error("RenderTemplate not in the hierarchy") }

/**
 * True when a template is drawn inside a floating card over a map (MapWithContentTemplate and
 * friends) instead of full screen: no own background, tighter header.
 */
internal val LocalInCard = staticCompositionLocalOf { false }

/** Runs a delegate call (binder IPC into the app); a dead or misbehaving app must not crash us. */
internal inline fun safeCall(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
    }
}

/** Sends a click, honouring ParkedOnlyOnClickListener while driving. */
internal fun TemplateHost.click(delegate: OnClickDelegate?) {
    if (delegate == null) return
    if (delegate.isParkedOnly && driving) {
        showParkedOnlyMessage()
        return
    }
    safeCall("click") { delegate.sendClick(done) }
}

/** Template header resolved from the new Header model or the deprecated title/headerAction. */
internal class HeaderSpec(
    val title: CarText?,
    val start: Action?,
    val end: List<Action>,
) {
    val isEmpty: Boolean get() = CarText.isNullOrEmpty(title) && start == null && end.isEmpty()
}

internal fun headerSpec(header: Header?, title: CarText? = null, headerAction: Action? = null, strip: ActionStrip? = null) =
    HeaderSpec(
        title = header?.title ?: title,
        start = header?.startHeaderAction ?: headerAction,
        end = header?.endHeaderActions.orEmpty() + strip?.actions.orEmpty(),
    )

/**
 * Full-screen frame shared by the non-map templates: header bar, content, and the template's
 * floating actions at the bottom end (ListTemplate/GridTemplate "fab" actions).
 */
@Composable
internal fun TemplateScaffold(
    header: HeaderSpec,
    modifier: Modifier = Modifier,
    fabActions: List<Action> = emptyList(),
    headerExtra: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val inCard = LocalInCard.current
    val bg = if (inCard) Modifier else Modifier.background(MaterialTheme.colorScheme.background)
    Column(modifier.fillMaxSize().then(bg)) {
        if (!header.isEmpty || headerExtra != null) HeaderBar(header, headerExtra)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
            if (fabActions.isNotEmpty()) {
                FloatingActions(fabActions, Modifier.align(Alignment.BottomEnd).padding(if (inCard) 12.dp else 24.dp))
            }
        }
    }
}

/** Header bar: start action (back / app icon), title, then end actions and action strip. */
@Composable
internal fun HeaderBar(header: HeaderSpec, extra: (@Composable RowScope.() -> Unit)? = null) {
    val d = LocalCarDimens.current
    val inCard = LocalInCard.current
    Row(
        Modifier.fillMaxWidth().height(d.touchTarget + if (inCard) 8.dp else 20.dp)
            .padding(horizontal = if (inCard) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (header.start != null) {
            ActionView(header.start, ActionLook.Header)
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(if (inCard) 12.dp else 16.dp))
        }
        CarTextView(
            header.title,
            Modifier.weight(1f),
            style = if (inCard) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (CarText.isNullOrEmpty(header.title)) Spacer(Modifier.weight(1f))
        extra?.invoke(this)
        if (header.end.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                header.end.forEach { ActionView(it, ActionLook.Strip) }
            }
        }
    }
}

/** Centered spinner for isLoading templates and items. */
@Composable
internal fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 5.dp)
    }
}

/** Centered calm message, for empty lists and unsupported content. */
@Composable
internal fun EmptyMessage(text: CarText?, fallback: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
        }
        if (CarText.isNullOrEmpty(text)) {
            Text(fallback, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        } else {
            CarTextView(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, textAlign = TextAlign.Center)
        }
    }
}

/** A rounded tonal card, the basic surface of the car UI (same look as the dashboard cards). */
@Composable
internal fun CarCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val r = LocalCarDimens.current.cardRadius
    Box(modifier.clip(RoundedCornerShape(r)).background(MaterialTheme.colorScheme.surfaceContainer), content = content)
}

/** Horizontal padding of full-screen content, aligned with the header title. */
internal val ContentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)
