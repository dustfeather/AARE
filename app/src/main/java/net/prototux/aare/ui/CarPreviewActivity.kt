package net.prototux.aare.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import net.prototux.aare.Settings
import net.prototux.aare.audio.MicRouter
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.MediaHub
import net.prototux.aare.ui.car.CarScreen

/**
 * The car screen on the phone itself, without a car: to try apps, check the layout, or use
 * the phone as a car display. Hosted apps get the phone's microphone.
 */
class CarPreviewActivity : ComponentActivity() {
    private lateinit var runtime: CarRuntime
    private val mic = MicRouter({ null }, {})

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runtime = CarRuntime(this, lifecycleScope, Settings(this), MediaHub(this, lifecycleScope), primary = false)
        val dm = resources.displayMetrics
        runtime.densityDpi = dm.densityDpi
        runtime.screenWidthDp = (dm.widthPixels / dm.density).toInt()
        runtime.screenHeightDp = (dm.heightPixels / dm.density).toInt()
        runtime.microphone = { sink, stopped -> mic.open(sink, stopped) }
        runtime.start()
        setContent { runtime.Root(Modifier.fillMaxSize().systemBarsPadding()) }
        // Through the dispatcher: with predictive back (targetSdk 36+ on Android 16) onBackPressed
        // is no longer called. Back walks the car screen back to the dashboard, then leaves.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (runtime.ui.screen == CarScreen.Dashboard) finish() else runtime.back()
            }
        })
    }

    override fun onDestroy() {
        mic.shutdown()
        runtime.stop()
        super.onDestroy()
    }
}
