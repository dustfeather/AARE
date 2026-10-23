package net.prototux.aare.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
        runtime = CarRuntime(this, lifecycleScope, Settings(this), MediaHub(this, lifecycleScope))
        val dm = resources.displayMetrics
        runtime.densityDpi = dm.densityDpi
        runtime.screenWidthDp = (dm.widthPixels / dm.density).toInt()
        runtime.screenHeightDp = (dm.heightPixels / dm.density).toInt()
        runtime.microphone = { sink, stopped -> mic.open(sink, stopped) }
        runtime.start()
        setContent { runtime.Root(Modifier.fillMaxSize().systemBarsPadding()) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (runtime.ui.screen == net.prototux.aare.ui.car.CarScreen.Dashboard) super.onBackPressed() else runtime.back()
    }

    override fun onDestroy() {
        mic.shutdown()
        runtime.stop()
        super.onDestroy()
    }
}
