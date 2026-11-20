package dev.nura.app.field.sync.core.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Opens the TCP connection over an established Wi-Fi Direct group. The Group Owner (a network
 * role, not necessarily the Admin) listens; the other device connects to the Group Owner address.
 *
 * `onOpened` receives every socket as soon as it exists so the caller can close it to abort a
 * blocking accept/connect when the user cancels.
 */
object TcpConnector {

    const val PORT = 8988
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val RETRY_DELAY_MS = 1_000L

    /** Group Owner side: waits for one client. */
    fun accept(port: Int = PORT, timeoutMs: Int, onOpened: (Closeable) -> Unit): Socket =
        ServerSocket().use { server ->
            onOpened(server)
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            server.soTimeout = timeoutMs
            try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                throw TransferException("The other device did not connect in time.", e)
            }
        }.also { it.tcpNoDelay = true }

    /** Client side: retries until the Group Owner's server socket is listening or [timeoutMs] passes. */
    suspend fun connect(
        host: InetAddress,
        port: Int = PORT,
        timeoutMs: Long,
        onOpened: (Closeable) -> Unit,
    ): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: IOException? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = Socket()
            onOpened(socket)
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                return socket
            } catch (e: IOException) {
                lastError = e
                runCatching { socket.close() }
                delay(RETRY_DELAY_MS)
            }
        }
        throw TransferException("Could not reach the other device. Make sure FieldSync is open on it.", lastError)
    }
}
