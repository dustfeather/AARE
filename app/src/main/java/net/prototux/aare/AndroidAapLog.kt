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

/** Also writes to the [DiagLog] the user sees in the app (not the debug lines). */
object DiagAapLog : AapLog {
    override fun d(msg: String) { Log.d("AareAap", msg) }
    override fun i(msg: String) { DiagLog.add(msg) }
    override fun w(msg: String, t: Throwable?) { Log.w("AareAap", msg, t); DiagLog.add(if (t != null) "$msg: ${t.message ?: t}" else msg) }
    override fun e(msg: String, t: Throwable?) { Log.e("AareAap", msg, t); DiagLog.add(if (t != null) "$msg: ${t.message ?: t}" else msg) }
}
