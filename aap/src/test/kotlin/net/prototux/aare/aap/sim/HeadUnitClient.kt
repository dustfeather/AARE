package net.prototux.aare.aap.sim

import net.prototux.aare.aap.SocketTransport
import java.io.File
import java.io.FileOutputStream
import java.net.Socket

/**
 * Desktop head unit: connects to a phone running AARE's head-unit server (through
 * `adb forward tcp:5277 tcp:5277`), records the projection and can tap the screen.
 *
 *   ./gradlew :aap:runHeadUnit --args="out.h264 20 1280x720 tap:640,360@8 back@12"
 *
 * Args: output file, seconds, resolution, then scripted events "tap:x,y@second", "back@second",
 * "voice@second", "native@second" / "projected@second" (video focus).
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "hu.h264" })
    val seconds = args.getOrNull(1)?.toInt() ?: 15
    val (w, h) = (args.getOrNull(2) ?: "1280x720").split("x").map { it.toInt() }
    val events = args.drop(3).map { e ->
        val (what, at) = e.split("@")
        at.toDouble() to what
    }.sortedBy { it.first }

    val socket = Socket("127.0.0.1", args.getOrNull(99)?.toInt() ?: 5277)
    FileOutputStream(out).use { video ->
        val hu = HeadUnitSimulator(SocketTransport(socket), width = w, height = h, dpi = if (h >= 1080) 240 else 160, maxUnacked = 4, videoOut = video)
        hu.start()
        val start = System.nanoTime()
        var next = 0
        while ((System.nanoTime() - start) / 1e9 < seconds && hu.closed.count > 0) {
            val t = (System.nanoTime() - start) / 1e9
            while (next < events.size && events[next].first <= t) {
                val what = events[next++].second
                println("[%.1fs] %s".format(t, what))
                when {
                    what.startsWith("tap:") -> what.removePrefix("tap:").split(",").let { hu.tap(it[0].toInt(), it[1].toInt()) }
                    what == "back" -> hu.key(4)
                    what == "home" -> hu.key(3)
                    what == "voice" -> hu.key(84)
                    what == "native" -> hu.requestFocusNative()
                    what == "projected" -> hu.requestFocusProjected()
                }
            }
            Thread.sleep(50)
        }
        println("frames=${hu.videoFrames.get()} bytes=${hu.videoBytes.get()} codecConfigs=${hu.codecConfigs.get()} " +
            "audio=${hu.audioChunks.get()} pings=${hu.pingsAnswered.get()} channels=${hu.openedChannels.sorted()} error=${hu.error}")
        println("phone: ${hu.phoneRequest?.deviceName} / ${hu.phoneRequest?.labelText}")
        hu.sayByeBye()
        Thread.sleep(500)
        hu.stop()
    }
}
