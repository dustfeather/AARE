package net.prototux.aare.transport

import android.util.Log
import net.prototux.aare.aap.SocketTransport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException

/**
 * The developer "head unit server": the phone listens and the head unit connects, the way
 * Android Auto's developer mode works with Google's DHU (through `adb forward tcp:5277 tcp:5277`),
 * openauto or headunit-revived. Loopback only unless [lan] is set: anyone who can connect gets
 * the car screen and microphone.
 */
class TcpServer(
    private val lan: Boolean,
    private val port: Int = PORT,
    private val onConnection: (SocketTransport) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress(), port))
        }
        server = s
        thread = Thread({
            Log.i(TAG, "listening on ${s.localSocketAddress}")
            while (!s.isClosed) {
                try {
                    val socket = s.accept()
                    Log.i(TAG, "head unit connected from ${socket.remoteSocketAddress}")
                    onConnection(SocketTransport(socket))
                } catch (e: SocketException) {
                    break // closed
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                }
            }
        }, "aare-tcp-server").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
    }

    companion object {
        const val PORT = 5277
        private const val TAG = "AareTcpServer"
    }
}
