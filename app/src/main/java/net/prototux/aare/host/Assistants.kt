package net.prototux.aare.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap

/** A voice assistant the steering-wheel voice button can open. */
data class AssistantApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    /** It has a car screen (a Car App Library app): it runs on the car screen and hears the car's microphone. */
    val hasCarApp: Boolean,
)

/**
 * Finds voice assistants and the phone's default one.
 *
 * The voice button opens the assistant the user picked in AARE, or by default the phone's own
 * default digital assistant. An assistant with a car app (Lapin, for example) runs on the car
 * screen; any other one is opened on the phone.
 */
object Assistants {

    /**
     * The app allowed to act on the car and read messages through [CarControlProvider]: the one
     * chosen in AARE ([chosen]), or else the phone's default digital assistant as set by the user.
     * Never a guess from the assist intent: any app can declare that intent filter.
     */
    fun authorized(context: Context, chosen: String?): String? = chosen ?: phoneDefaultSetting(context)

    /**
     * Settings > Apps > Default apps > Digital assistant, stored as a component name; null when
     * it is "None" or the phone doesn't let apps read it.
     */
    fun phoneDefaultSetting(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, "assistant") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { ComponentName.unflattenFromString(it)?.packageName }

    /**
     * Package of the phone's default digital assistant, or null if none is set: for what the UI
     * shows and opens by default, not for authorization (see [authorized]).
     */
    fun phoneDefault(context: Context): String? {
        phoneDefaultSetting(context)?.let { return it }
        // Some phones don't let apps read that setting: fall back to the default handler of the assist intent.
        val pm = context.packageManager
        for (action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
            val pkg = pm.resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            // "android" is the chooser: several apps, no default.
            if (pkg != null && pkg != "android") return pkg
        }
        return null
    }

    /**
     * Everything that can be chosen: car apps other than navigation, and phone apps that answer
     * the assist or voice-command intents. One entry per package, car apps first.
     */
    fun query(context: Context, carApps: List<CarAppInfo>, iconSizePx: Int = 128): List<AssistantApp> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, AssistantApp>()
        for (app in carApps) {
            if (app.isNavigation || app.packageName in result) continue
            result[app.packageName] = AssistantApp(app.packageName, app.label, app.icon, hasCarApp = true)
        }
        for (action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
            for (ri in pm.queryIntentActivities(Intent(action), 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg in result || pkg == context.packageName) continue
                val appInfo = ri.activityInfo.applicationInfo
                val icon = runCatching { appInfo.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull()
                result[pkg] = AssistantApp(pkg, appInfo.loadLabel(pm).toString(), icon, hasCarApp = false)
            }
        }
        return result.values.toList()
    }

    /** Label of an installed app, or null. */
    fun label(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrNull()

    /**
     * Opens [packageName]'s voice input on the phone, for an assistant without a car app.
     * Returns false if it has nothing to open. Android may still keep it in the background
     * when AARE isn't in the foreground on the phone.
     */
    fun startOnPhone(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        for (action in listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)) {
            val intent = Intent(action).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pm.resolveActivity(intent, 0) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
