package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The OkHttp socket behind the flow, against MockWebServer's WebSocket upgrade — connect, the
 * open frames, frames in order, a server close ending the flow, a refused upgrade reconnecting
 * on the policy, and a collector cancel closing the live socket.
 */
class OkHttpSocketClientTest {

    private val server = MockWebServer()
    private val client = OkHttpSocketClient(OkHttpClient())

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.close()

    private fun wsUrl(path: String) = server.url(path).toString().replace("http://", "ws://")

    /** A server side that records what the client sent and replies with [replies] to the first client frame. */
    private fun socketResponse(received: MutableList<String>, replies: List<String>, closeAfter: Boolean = true, closed: CountDownLatch? = null): MockResponse =
        MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            private var seen = 0

            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"pong"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                seen += 1
                if (seen == 1) {
                    replies.forEach { webSocket.send(it) }
                    if (closeAfter) webSocket.close(1000, "done")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed?.countDown()
            }
        }).build()

    @Test
    fun `connects, sends the open frames, delivers the frames in order and ends on the server close`() = runTest {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(socketResponse(received, listOf("""{"type":"stream.chunk","message":"Dear"}""", """{"type":"stream.end"}""")))

        val events = client.connect(wsUrl("/ws/ai/lawris-ai/"), openFrames = listOf(SocketSubscription.chatJoinFrame("t-1"))).toList()

        assertThat(received).containsExactly("""{"thread_id":"t-1"}""")
        assertThat(events[0]).isEqualTo(SocketEvent.Connected(0))
        assertThat(events[1]).isEqualTo(SocketEvent.Frame("""{"type":"pong"}"""))
        assertThat(events[2]).isEqualTo(SocketEvent.Frame("""{"type":"stream.chunk","message":"Dear"}"""))
        assertThat(events[3]).isEqualTo(SocketEvent.Frame("""{"type":"stream.end"}"""))
        assertThat(events[4]).isEqualTo(SocketEvent.Closed(1000, "done"))
        assertThat(events.size).isEqualTo(5)
        // The default policy does not reconnect after a server close: one upgrade request only.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a refused upgrade reconnects on the policy, re-sending the open frames, and gives up after the last attempt`() = runTest {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse(code = 404))
        server.enqueue(socketResponse(received, listOf("""{"type":"pong"}""")))

        val events = client.connect(
            wsUrl("/ws/firm-knowledge/"),
            openFrames = listOf("""{"action":"authenticate","token":"jwt"}"""),
            policy = ReconnectPolicy(baseMillis = 1, maxMillis = 1, maxAttempts = 1),
        ).toList()

        assertThat(events[0]).isInstanceOf<SocketEvent.Closed>() // the 404
        assertThat(events[1]).isEqualTo(SocketEvent.Connected(1))
        assertThat(received).containsExactly("""{"action":"authenticate","token":"jwt"}""")
        assertThat(server.requestCount).isEqualTo(2)

        // A second refusal past maxAttempts ends the flow instead of looping.
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 404))
        val refused = client.connect(wsUrl("/ws/x/"), policy = ReconnectPolicy(baseMillis = 1, maxMillis = 1, maxAttempts = 1)).toList()
        assertThat(refused.size).isEqualTo(2)
        assertThat(refused.all { it is SocketEvent.Closed }).isTrue()
    }

    @Test
    fun `a server close reconnects when the policy says so, a frame resets the attempt count, and a refusal past the cap ends it`() = runTest {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(socketResponse(received, listOf("""{"type":"task_progress"}""")))
        server.enqueue(socketResponse(received, listOf("""{"type":"task_notification"}""")))
        server.enqueue(MockResponse(code = 404)) // the third connect is refused: attempt 2 > maxAttempts 1 → the flow ends

        val events = client.connect(
            wsUrl("/ws/dms/notifications/u-1/"),
            openFrames = listOf("""{"type":"ping"}"""),
            policy = ReconnectPolicy(baseMillis = 1, maxMillis = 1, maxAttempts = 1, reconnectAfterServerClose = true),
        ).toList()

        assertThat(events.filterIsInstance<SocketEvent.Connected>().map { it.attempt }).containsExactly(0, 1)
        assertThat(events.filterIsInstance<SocketEvent.Frame>().map { it.text }).containsExactly(
            """{"type":"pong"}""", """{"type":"task_progress"}""", """{"type":"pong"}""", """{"type":"task_notification"}""",
        )
        assertThat(events.last()).isInstanceOf<SocketEvent.Closed>()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `cancelling the collector closes the live socket with a normal closure`() = runTest {
        val closed = CountDownLatch(1)
        server.enqueue(socketResponse(CopyOnWriteArrayList(), emptyList(), closeAfter = false, closed = closed))

        val first = client.connect(wsUrl("/ws/ai/contractual-analysis/an-1/"), openFrames = listOf("""{"action":"ping"}""")).first { it is SocketEvent.Frame }

        assertThat(first).isEqualTo(SocketEvent.Frame("""{"type":"pong"}"""))
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
    }
}
