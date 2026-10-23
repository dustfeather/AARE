package net.prototux.aare

import android.util.Log
import net.prototux.aare.aap.AapLog

/** Protocol logs go to logcat under "AareAap". */
object AndroidAapLog : AapLog {
    override fun d(msg: String) { Log.d("AareAap", msg) }
    override fun i(msg: String) { Log.i("AareAap", msg) }
    override fun w(msg: String, t: Throwable?) { Log.w("AareAap", msg, t) }
    override fun e(msg: String, t: Throwable?) { Log.e("AareAap", msg, t) }
}
