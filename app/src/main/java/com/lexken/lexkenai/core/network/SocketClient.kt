package com.lexken.lexkenai.core.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * What a socket collector sees. Frames are the server's text messages, unparsed — each
 * feature's frame parser knows its own vocabulary.
 */
sealed interface SocketEvent {
    /** The connection (re)opened; [attempt] is 0 for the first, then the reconnect count. */
    data class Connected(val attempt: Int) : SocketEvent
    data class Frame(val text: String) : SocketEvent

    /** The server closed the socket (`stream.end` closes the chat socket) or the link dropped. */
    data class Closed(val code: Int?, val reason: String?) : SocketEvent
}

/**
 * One WebSocket per screen (the web's `wsService.js` model): the [openFrames] are sent after
 * every (re)connect — the chat join `{thread_id}`, the Firm Knowledge `{action: "authenticate"}`
 * — and the flow ends when the collector cancels or the [ReconnectPolicy] gives up.
 *
 * Keep-alives are WebSocket ping/pong control frames (the client's `pingInterval`), never JSON:
 * `GeneralAIConsumer.receive` reads `thread_id` off every text frame and closes the socket on
 * one without it.
 */
interface SocketClient {
    fun connect(url: String, openFrames: List<String> = emptyList(), policy: ReconnectPolicy = ReconnectPolicy()): Flow<SocketEvent>

    companion object {
        /** No live rail at all: every feature keeps its poll twin. */
        val NONE: SocketClient = object : SocketClient {
            override fun connect(url: String, openFrames: List<String>, policy: ReconnectPolicy): Flow<SocketEvent> = emptyFlow()
        }
    }
}

/**
 * The web's backoff (`wsService.js`): the delay grows with the attempt, capped, and the client
 * stops after [maxAttempts] in a row. A frame resets the count. [reconnectAfterServerClose] is
 * false for the chat socket, which the server closes on `stream.end` by design.
 */
data class ReconnectPolicy(
    val baseMillis: Long = 1_000,
    val maxMillis: Long = 15_000,
    val maxAttempts: Int = 5,
    val reconnectAfterServerClose: Boolean = false,
) {
    /** 1 s, 2 s, 4 s … capped — the attempt is 1-based. */
    fun delayFor(attempt: Int): Long = (baseMillis * (1L shl (attempt - 1).coerceIn(0, 20))).coerceAtMost(maxMillis)

    fun shouldRetry(attempt: Int): Boolean = attempt <= maxAttempts
}

/**
 * `wss://host/ws/…` from the api base (`https://host/api/` → the host root, `/api` dropped).
 * Mirrors the `WS_BASE_URL` derivation in the build script; the paths are the consumers
 * `lawris_django/asgi.py` routes, which carry no `/api` prefix.
 */
object SocketUrls {
    fun base(apiBaseUrl: String): String {
        val root = apiBaseUrl.trim().trimEnd('/').removeSuffix("/api")
        val ws = when {
            root.startsWith("https://") -> "wss://" + root.removePrefix("https://")
            root.startsWith("http://") -> "ws://" + root.removePrefix("http://")
            else -> root
        }
        return "$ws/ws/"
    }

    fun chat(apiBaseUrl: String) = base(apiBaseUrl) + "ai/lawris-ai/"
    fun dmsNotifications(apiBaseUrl: String, userId: String) = base(apiBaseUrl) + "dms/notifications/$userId/"
    fun contractAnalysis(apiBaseUrl: String, analysisId: String) = base(apiBaseUrl) + "ai/contractual-analysis/$analysisId/"
    fun gitContract(apiBaseUrl: String, repositoryId: String) = base(apiBaseUrl) + "ai/gitcontract/$repositoryId/"
    fun firmKnowledge(apiBaseUrl: String) = base(apiBaseUrl) + "firm-knowledge/"
}

/**
 * OkHttp's WebSocket behind a cold flow. Reconnects on a dropped link (and, when the policy
 * says so, after a server close), re-sending the open frames each time; a clean cancel closes
 * the socket with 1000.
 */
class OkHttpSocketClient(private val client: OkHttpClient) : SocketClient {
    override fun connect(url: String, openFrames: List<String>, policy: ReconnectPolicy): Flow<SocketEvent> = callbackFlow {
        var attempt = 0
        var socket: WebSocket? = null
        val drops = Channel<Int?>(Channel.CONFLATED)
        try {
            while (true) {
                val connectAttempt = attempt
                socket = client.newWebSocket(
                    Request.Builder().url(url).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openFrames.forEach { webSocket.send(it) }
                            trySend(SocketEvent.Connected(connectAttempt))
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            attempt = 0 // a live server resets the backoff
                            trySend(SocketEvent.Frame(text))
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            trySend(SocketEvent.Closed(code, reason))
                            drops.trySend(code)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            trySend(SocketEvent.Closed(null, t.message))
                            drops.trySend(null)
                        }
                    },
                )
                val code = drops.receive()
                val serverClosed = code != null
                if (serverClosed && !policy.reconnectAfterServerClose) break
                attempt += 1
                if (!policy.shouldRetry(attempt)) break
                delay(policy.delayFor(attempt))
            }
        } finally {
            // Collector cancelled, the policy gave up or the server closed: the live socket goes with the flow.
            socket?.close(NORMAL_CLOSURE, "done")
        }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED)

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
