package net.prototux.aare.host

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Telephony
import androidx.core.graphics.drawable.toBitmap
import net.prototux.aare.media.MediaDiscovery
import org.xmlpull.v1.XmlPullParser

/** An app that declares Android Auto support but that AARE doesn't show, and why. */
data class UnsupportedCarApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    /** The `<uses name="…">` entries of its `automotive_app_desc`. */
    val uses: Set<String>,
    val reason: String,
)

/** A messaging app, shown in the car with the conversations of its notifications. */
data class MessagingApp(val packageName: String, val label: String, val icon: Bitmap?)

/**
 * Finds every app that declares Android Auto support (`com.google.android.gms.car.application`
 * meta-data): the messaging apps among them, and the ones AARE can't show, to tell the user.
 */
object AndroidAutoApps {
    private const val META_CAR_APPLICATION = "com.google.android.gms.car.application"
    private val MESSAGING_USES = setOf("notification", "sms")

    /**
     * Messaging apps, as Android Auto finds them: apps declaring `<uses name="notification"/>`
     * (or `sms`), plus the default SMS app.
     */
    fun messagingApps(context: Context, iconSizePx: Int = 128): List<MessagingApp> {
        val pm = context.packageManager
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return declaring(context)
            .filter { (app, uses) -> uses.any { it in MESSAGING_USES } || app.packageName == sms }
            .map { (app, _) -> MessagingApp(app.packageName, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull()) }
            .let { found ->
                if (sms == null || found.any { it.packageName == sms }) found
                else found + listOfNotNull(runCatching {
                    val app = pm.getApplicationInfo(sms, 0)
                    MessagingApp(sms, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull())
                }.getOrNull())
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Apps declaring Android Auto support that AARE lists neither as car apps ([carApps]) nor as media or messaging apps. */
    fun unsupported(context: Context, carApps: Collection<String>, iconSizePx: Int = 96): List<UnsupportedCarApp> {
        val pm = context.packageManager
        val shown = carApps.toSet() + mediaPackages(pm)
        return declaring(context)
            .filter { (app, uses) -> app.packageName !in shown && uses.none { it in MESSAGING_USES } }
            .map { (app, uses) ->
                UnsupportedCarApp(
                    packageName = app.packageName,
                    label = app.loadLabel(pm).toString(),
                    icon = runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(),
                    uses = uses,
                    reason = reason(uses),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Enabled apps with `com.google.android.gms.car.application` meta-data, with their `<uses>` entries. */
    private fun declaring(context: Context): List<Pair<ApplicationInfo, Set<String>>> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.enabled && it.metaData?.containsKey(META_CAR_APPLICATION) == true && it.packageName != context.packageName }
            .map { it to uses(pm, it) }
    }

    /** Packages with a media browser service, which AARE lists as media apps (see MediaDiscovery). */
    private fun mediaPackages(pm: PackageManager): Set<String> =
        listOf(MediaDiscovery.ACTION_MEDIA3_LIBRARY, MediaDiscovery.ACTION_LEGACY_BROWSER).flatMap { action ->
            @Suppress("DEPRECATION") pm.queryIntentServices(Intent(action), 0)
                .mapNotNull { ri -> ri.serviceInfo?.takeIf { it.exported }?.packageName }
        }.toSet()

    private fun reason(uses: Set<String>): String = when {
        "media" in uses -> "Declares media support but has no media browser service AARE can use"
        "template" in uses -> "Declares a car app but has no Car App Library service AARE can use"
        uses.isEmpty() -> "Uses Google's private Android Auto interface, which only Android Auto can host"
        else -> "Uses Google's private Android Auto interface (${uses.joinToString()}), which only Android Auto can host"
    }

    /** The `<uses name="…">` entries of the app's `automotive_app_desc` XML. */
    private fun uses(pm: PackageManager, app: ApplicationInfo): Set<String> {
        val res = app.metaData?.getInt(META_CAR_APPLICATION, 0)?.takeIf { it != 0 } ?: return emptySet()
        return runCatching {
            pm.getResourcesForApplication(app).getXml(res).use { xml ->
                buildSet {
                    var type = xml.eventType
                    while (type != XmlPullParser.END_DOCUMENT) {
                        if (type == XmlPullParser.START_TAG && xml.name == "uses") xml.getAttributeValue(null, "name")?.let { add(it) }
                        type = xml.next()
                    }
                }
            }
        }.getOrDefault(emptySet())
    }
}
