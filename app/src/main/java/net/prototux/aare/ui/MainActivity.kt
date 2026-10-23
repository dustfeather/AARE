package net.prototux.aare.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.cert.CertificateStore
import net.prototux.aare.host.AndroidAutoApps
import net.prototux.aare.host.AssistantApp
import net.prototux.aare.host.Assistants
import net.prototux.aare.host.CarAppCatalog
import net.prototux.aare.host.CarAppInfo
import net.prototux.aare.host.UnsupportedCarApp
import net.prototux.aare.mirror.MirrorTouchService
import net.prototux.aare.service.ProjectionService
import net.prototux.aare.ui.theme.PhoneTheme
import java.text.DateFormat

/** The phone side: what's connected, what's set up, and the settings. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyDebugExtras(intent)
        setContent { PhoneTheme(dark = isSystemInDarkTheme()) { Surface(Modifier.fillMaxSize()) { Home() } } }
    }

    // singleTask: when AARE is already open, ./headunit.sh's intent arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyDebugExtras(intent)) {
            ProjectionService.startIdleIfEnabled(this)
            recreate() // the settings screen shows the new state
        }
    }

    /** Debug builds only: lets ./headunit.sh switch the head unit server on over adb. Returns true if applied. */
    private fun applyDebugExtras(intent: Intent?): Boolean {
        if (!net.prototux.aare.BuildConfig.DEBUG || intent?.hasExtra("hu_server") != true) return false
        Settings(this).headUnitServer = intent.getBooleanExtra("hu_server", false)
        return true
    }
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val certs = remember { CertificateStore(ctx) }
    val state by ProjectionService.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    // re-check permissions and apps whenever the user comes back
    LaunchedEffect(Unit) { ProjectionService.startIdleIfEnabled(ctx) }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header() }
        item { StatusCard(state) }
        item { Setup(state, refresh) { refresh++ } }
        item { ConnectionSettings(settings) }
        item { CarScreenSettings(settings) }
        item { AppsCard(settings) }
        item { AssistantCard(settings) }
        item { CertificatesCard(certs, state) }
        item { LogCard() }
        item { About() }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("AARE", style = MaterialTheme.typography.headlineSmall)
            Text("Open-source car projection", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun StatusCard(state: ProjectionService.State) {
    val ctx = LocalContext.current
    val bg = if (state.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(24.dp)) {
        Text(if (state.connected) state.status else "Not connected", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        val detail = when {
            state.connected -> listOf(state.transport, state.video, "certificate: ${state.certificate}").filter { it.isNotBlank() }.joinToString("\n")
            else -> "Plug the phone into the car's Android Auto USB port, or turn on the head unit server below."
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Preview car screen", Icons.Rounded.Tv) { ctx.startActivity(Intent(ctx, CarPreviewActivity::class.java)) }
            if (state.connected) Pill("Disconnect", null) { ProjectionService.stop(ctx) }
        }
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- setup checklist

@Composable
private fun Setup(state: ProjectionService.State, refresh: Int, recheck: () -> Unit) {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { recheck() }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK && r.data != null) ProjectionService.giveAudioConsent(ctx, r.resultCode, r.data!!)
        recheck()
    }
    val runtimePerms = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
    @Suppress("UNUSED_EXPRESSION") refresh
    val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    Section("Setup") {
        Check("Permissions", "Microphone, location, Bluetooth, nearby Wi-Fi, notifications", runtimePerms.all(::granted)) {
            permissions.launch(runtimePerms.toTypedArray())
        }
        Check("Notification access", "Messages in the car, and now-playing for any app", notificationAccess) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Check("Car audio and phone screen", "Play the phone's sound in the car and show the phone screen there. Android asks to capture the screen: choose Entire screen", state.audioCapture) {
            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            // Entire screen, not one app: the phone screen in the car mirrors the whole display.
            consent.launch(if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent())
        }
        val touch = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(ctx, MirrorTouchService::class.java) }
        Check("Touch on the phone screen (optional)", "Use the phone screen from the car: turn on AARE in Accessibility. Sideloaded apps may first need \"Allow restricted settings\" in AARE's app info", touch) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}

@Composable
private fun Check(title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !done, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) Text("Set up", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun ConnectionSettings(settings: Settings) {
    val ctx = LocalContext.current
    var huServer by remember { mutableStateOf(settings.headUnitServer) }
    var lan by remember { mutableStateOf(settings.headUnitServerLan) }
    var wireless by remember { mutableStateOf(settings.wireless) }
    var fps60 by remember { mutableStateOf(settings.prefer60fps) }
    var maxH by remember { mutableIntStateOf(settings.maxVideoHeight) }
    Section("Connection") {
        Toggle("Wireless", "Connect to paired head units over Wi-Fi", wireless) { wireless = it; settings.wireless = it }
        Toggle("Head unit server", "Listen on port 5277, like Android Auto's developer mode (DHU, openauto, Headunit Revived)", huServer) {
            huServer = it; settings.headUnitServer = it
            if (it) ProjectionService.startIdleIfEnabled(ctx) else if (!ProjectionService.state.value.connected) ProjectionService.stop(ctx)
        }
        if (huServer) Toggle("Accept from the network", "Otherwise only through adb forward. Anyone who connects sees the car screen.", lan) {
            lan = it; settings.headUnitServerLan = it
        }
        Toggle("Prefer 60 fps", "Smoother when the car supports it", fps60) { fps60 = it; settings.prefer60fps = it }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Maximum resolution", style = MaterialTheme.typography.bodyLarge)
                Text("Lower uses less battery and bandwidth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOf(480, 720, 1080, 1440).forEach { h ->
                val sel = h == maxH
                Text("${h}p", style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { maxH = h; settings.maxVideoHeight = h }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CarScreenSettings(settings: Settings) {
    var theme by remember { mutableStateOf(settings.carTheme) }
    var rail by remember { mutableStateOf(settings.railPosition) }
    Section("Car screen") {
        Choices("Colors", listOf(Settings.THEME_AUTO to "Car's day/night", Settings.THEME_DARK to "Dark", Settings.THEME_LIGHT to "Light"), theme) {
            theme = it; settings.carTheme = it
        }
        Spacer(Modifier.height(12.dp))
        Choices("Bar", listOf(Settings.RAIL_AUTO to "Driver's side", Settings.RAIL_LEFT to "Left", Settings.RAIL_RIGHT to "Right", Settings.RAIL_BOTTOM to "Bottom"), rail) {
            rail = it; settings.railPosition = it
        }
    }
}

/** A labelled row of mutually exclusive options. */
@Composable
private fun Choices(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(6.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------------------------------------------------------------- apps

@Composable
private fun AppsCard(settings: Settings) {
    val ctx = LocalContext.current
    val apps = remember { CarAppCatalog.query(ctx) }
    var hidden by remember { mutableStateOf(settings.hiddenApps) }
    Section("Car apps") {
        if (apps.isEmpty()) {
            Text("No car apps found. Install CoMaps, Organic Maps or another Android Auto app.", style = MaterialTheme.typography.bodyMedium)
        }
        apps.forEach { app ->
            AppRow(app, visible = app.packageName !in hidden,
                onVisible = { v -> hidden = if (v) hidden - app.packageName else hidden + app.packageName; settings.hiddenApps = hidden })
        }
        var phoneScreen by remember { mutableStateOf(settings.phoneScreen) }
        Toggle("Phone screen", "Show the phone's own screen in the car, for apps AARE can't host (Google Maps, Waze). Paused while driving unless a navigation app is guiding", phoneScreen) {
            phoneScreen = it; settings.phoneScreen = it
        }
        val unsupported by produceState(emptyList<UnsupportedCarApp>()) {
            value = withContext(Dispatchers.IO) { AndroidAutoApps.unsupported(ctx, apps.map { it.packageName }) }
        }
        if (unsupported.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Android Auto apps AARE can't show", style = MaterialTheme.typography.titleSmall)
            unsupported.forEach { UnsupportedRow(it) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Media apps (Tempus, podcasts, radio…) appear automatically. Apps built with recent versions of Google's car library only accept hosts in their allow-list; see the README.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppRow(app: CarAppInfo, visible: Boolean, onVisible: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            val kind = if (app.isNavigation) "Navigation"
                else app.categories.firstOrNull()?.substringAfterLast('.')?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "App"
            Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = visible, onCheckedChange = onVisible)
    }
}

@Composable
private fun UnsupportedRow(app: UnsupportedCarApp) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(app.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- assistant

/** Which assistant the steering-wheel voice button opens: the phone's default, or one picked here. */
@Composable
private fun AssistantCard(settings: Settings) {
    val ctx = LocalContext.current
    val choices = remember { Assistants.query(ctx, CarAppCatalog.query(ctx)) }
    val phoneDefault = remember { Assistants.phoneDefault(ctx) }
    var chosen by remember { mutableStateOf(settings.assistantPackage) }
    fun choose(pkg: String?) { chosen = pkg; settings.assistantPackage = pkg }
    Section("Voice assistant") {
        val defaultLabel = phoneDefault?.let { pkg -> choices.firstOrNull { it.packageName == pkg }?.label ?: Assistants.label(ctx, pkg) ?: pkg }
        AssistantRow(
            title = "Same as the phone",
            subtitle = defaultLabel ?: "No default assistant set on the phone",
            app = null, selected = chosen == null, onSelect = { choose(null) },
        )
        choices.forEach { app ->
            AssistantRow(
                title = app.label,
                subtitle = if (app.hasCarApp) "On the car screen, with the car's microphone" else "Opens on the phone",
                app = app, selected = chosen == app.packageName, onSelect = { choose(app.packageName) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("The steering-wheel voice button and the rail's microphone open this assistant. Assistants with a car app, such as Lapin, work best.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AssistantRow(title: String, subtitle: String, app: AssistantApp?, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        app?.icon?.let {
            Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- certificates

@Composable
private fun CertificatesCard(certs: CertificateStore, state: ProjectionService.State) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(certs.entries()) }
    var pickCert by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var p12 by remember { mutableStateOf<Uri?>(null) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { keyUri ->
        val certUri = pickCert ?: return@rememberLauncherForActivityResult
        if (keyUri == null) return@rememberLauncherForActivityResult
        error = runCatching {
            val cert = ctx.contentResolver.openInputStream(certUri)!!.use { it.readBytes().decodeToString() }
            val key = ctx.contentResolver.openInputStream(keyUri)!!.use { it.readBytes().decodeToString() }
            certs.importPem(certUri.lastPathSegment?.substringAfterLast('/') ?: "imported", cert, key)
            entries = certs.entries()
        }.exceptionOrNull()?.message
    }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment ?: ""
        if (name.endsWith(".p12", true) || name.endsWith(".pfx", true)) p12 = uri
        else { pickCert = uri; keyPicker.launch(arrayOf("*/*")) }
    }
    Section("Phone certificates") {
        Text("Head units check the phone's certificate. Open-source head units accept the generated one; cars using Google's head unit software only accept a certificate issued by Google, which AARE cannot include. You can import one you have the right to use (PEM certificate + PKCS#8 key, or PKCS#12).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${e.subject.take(60)} · until ${DateFormat.getDateInstance().format(e.notAfter)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!e.selfSigned || e.name != "Generated (self-signed)") TextButton(onClick = { certs.remove(e); entries = certs.entries() }) { Text("Remove") }
            }
        }
        TextButton(onClick = { certPicker.launch(arrayOf("*/*")) }) { Text("Import certificate…") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    p12?.let { uri ->
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { p12 = null },
            title = { Text("PKCS#12 password") },
            text = { OutlinedTextField(password, { password = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    error = runCatching { certs.importPkcs12(uri.lastPathSegment ?: "imported", uri, password.toCharArray()); entries = certs.entries() }
                        .exceptionOrNull()?.message
                    p12 = null
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { p12 = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogCard() {
    val log by DiagLog.lines.collectAsState()
    if (log.isEmpty()) return
    Section("Connection log") {
        log.takeLast(40).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { DiagLog.clear() }) { Text("Clear") }
    }
}

@Composable
private fun About() {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("AARE ${net.prototux.aare.BuildConfig.VERSION_NAME} · GPL-3.0-or-later · Not affiliated with Google. Android Auto is a trademark of Google LLC.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
