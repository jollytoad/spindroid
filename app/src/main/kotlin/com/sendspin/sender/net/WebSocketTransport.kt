package com.sendspin.sender.net

import com.sendspin.protocol.session.SessionTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * The WebSocket that carries the session.
 *
 * The transport writes to the socket and reports what arrives. The
 * session decides what the messages mean.
 */
class WebSocketTransport(
    private val url: String,
    private val onText: (String) -> Unit,
    private val onBinary: (ByteArray) -> Unit,
    private val onClosed: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : SessionTransport {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    /** Opens the socket. */
    fun open() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener)
    }

    override fun sendText(text: String) {
        socket?.send(text)
    }

    override fun sendBinary(bytes: ByteArray) {
        socket?.send(bytes.toByteString())
    }

    override fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    /** Releases the resources of the transport. */
    fun release() {
        close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            onBinary(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            onClosed(reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onClosed(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onFailure(t)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val PING_INTERVAL_SECONDS = 20L
    }
}
