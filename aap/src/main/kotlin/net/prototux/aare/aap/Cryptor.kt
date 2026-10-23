package net.prototux.aare.aap

import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS for AAP, driven over in-memory buffers.
 *
 * The TLS bytes never touch the socket directly: during the handshake they travel inside
 * SSL_HANDSHAKE control messages, and afterwards each encrypted frame payload is one TLS record.
 * So this wraps an [SSLEngine] and exposes byte-array in/out operations.
 *
 * The head unit is the TLS client and the phone the TLS server. Head units present a certificate
 * chained to Google's automotive CA; we accept any (we are not the party that needs protecting),
 * but keep the peer chain for diagnostics.
 *
 * The phone runs it in server mode; [client] mode exists for the simulated head unit.
 *
 * Not thread safe: the session calls [encrypt] from its writer thread and [decrypt] from its
 * reader thread only after the handshake, which SSLEngine supports (wrap and unwrap are
 * independent once the handshake has finished).
 */
class Cryptor(
    certificate: X509Certificate,
    privateKey: PrivateKey,
    chain: List<X509Certificate> = emptyList(),
    /** true to play the head unit's side (TLS client), used by the simulated head unit in tests. */
    client: Boolean = false,
) {

    private val engine: SSLEngine

    init {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("aap", privateKey, CharArray(0), (listOf(certificate) + chain).toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(AcceptAll), SecureRandom())
        engine = ctx.createSSLEngine().apply {
            useClientMode = client
            // Head units speak TLS 1.2; some ignore or choke on 1.3 extensions.
            val wanted = supportedProtocols.filter { it == "TLSv1.2" }
            if (wanted.isNotEmpty()) enabledProtocols = wanted.toTypedArray()
            // Ask for the head unit's certificate (it always sends one) but never require it.
            if (!client) wantClientAuth = true
            // Modern head units negotiate ECDHE+GCM; some legacy ones only offer RSA key exchange
            // with CBC. Offer everything the platform has except the anonymous/null/export suites.
            enabledCipherSuites = supportedCipherSuites.filter { cs ->
                cs.startsWith("TLS_") && listOf("_anon_", "_NULL_", "_EXPORT_", "_DES_", "_RC4_", "_SCSV").none { it in cs }
            }.sortedBy { if ("GCM" in it || "CHACHA" in it) 0 else 1 }.toTypedArray()
        }
    }

    private val session get() = engine.session
    private var netOut: ByteBuffer = ByteBuffer.allocate(session.packetBufferSize)
    private var appIn: ByteBuffer = ByteBuffer.allocate(session.applicationBufferSize)
    private val empty = ByteBuffer.allocate(0)

    @Volatile var handshakeDone = false
        private set

    /** The head unit's certificate chain, once the handshake is done. */
    val peerCertificates: List<X509Certificate>
        get() = runCatching { engine.session.peerCertificates.filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())

    val protocol: String get() = engine.session.protocol
    val cipherSuite: String get() = engine.session.cipherSuite

    fun beginHandshake() = engine.beginHandshake()

    /**
     * Feeds handshake bytes received from the head unit (may be empty to just pump the engine)
     * and returns the bytes to send back (possibly empty).
     */
    fun handshake(incoming: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(incoming)
        val out = java.io.ByteArrayOutputStream()
        loop@ while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    if (!input.hasRemaining() && engine.handshakeStatus == HandshakeStatus.NEED_UNWRAP) break@loop
                    appIn.clear()
                    val r = engine.unwrap(input, appIn)
                    when (r.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> break@loop // wait for more bytes
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                        SSLEngineResult.Status.CLOSED -> throw AapException("TLS closed during handshake")
                        SSLEngineResult.Status.OK -> Unit
                    }
                }
                HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val r = engine.wrap(empty, netOut)
                    if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = ByteBuffer.allocate(netOut.capacity() * 2); continue@loop
                    }
                    netOut.flip()
                    out.write(netOut.array(), 0, netOut.limit())
                    if (r.status == SSLEngineResult.Status.CLOSED) throw AapException("TLS closed during handshake")
                }
                HandshakeStatus.NEED_TASK -> {
                    while (true) engine.delegatedTask?.run() ?: break
                }
                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> {
                    handshakeDone = true
                    break@loop
                }
                else -> break@loop
            }
        }
        if (engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING) handshakeDone = true
        return out.toByteArray()
    }

    /** Encrypts one plaintext chunk (≤ [Frame.MAX_PAYLOAD]) into TLS record bytes. */
    fun encrypt(plain: ByteArray, offset: Int, length: Int): ByteArray {
        val input = ByteBuffer.wrap(plain, offset, length)
        val out = java.io.ByteArrayOutputStream(length + 64)
        while (input.hasRemaining()) {
            netOut.clear()
            val r = engine.wrap(input, netOut)
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { netOut = ByteBuffer.allocate(netOut.capacity() * 2); continue }
                SSLEngineResult.Status.CLOSED -> throw AapException("TLS closed")
                else -> Unit
            }
            netOut.flip()
            out.write(netOut.array(), 0, netOut.limit())
        }
        return out.toByteArray()
    }

    /** Decrypts the TLS record(s) of one frame payload. */
    fun decrypt(record: ByteArray, offset: Int, length: Int): ByteArray {
        val input = ByteBuffer.wrap(record, offset, length)
        val out = java.io.ByteArrayOutputStream(length)
        while (input.hasRemaining()) {
            appIn.clear()
            val r = engine.unwrap(input, appIn)
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { appIn = ByteBuffer.allocate(appIn.capacity() * 2); continue }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw AapException("truncated TLS record in frame")
                SSLEngineResult.Status.CLOSED -> throw AapException("TLS closed")
                SSLEngineResult.Status.OK -> Unit
            }
            appIn.flip()
            out.write(appIn.array(), 0, appIn.limit())
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break
        }
        return out.toByteArray()
    }

    private object AcceptAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

class AapException(message: String, cause: Throwable? = null) : Exception(message, cause)
