package net.prototux.aare.transport

import android.net.LocalSocket
import net.prototux.aare.aap.Transport
import java.io.InputStream
import java.io.OutputStream

/** A [Transport] over an accepted unix socket: the developer head unit server's adb forward. */
class LocalSocketTransport(private val socket: LocalSocket, override val name: String = "adb") : Transport {
    init {
        // Like SocketTransport: a big send buffer keeps the video writer from stalling on key frames.
        runCatching { socket.sendBufferSize = 512 * 1024 }
        runCatching { socket.receiveBufferSize = 256 * 1024 }
    }

    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}
