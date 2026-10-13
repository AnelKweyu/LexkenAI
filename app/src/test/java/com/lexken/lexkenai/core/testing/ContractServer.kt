package com.lexken.lexkenai.core.testing

import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The two backends as fixtures: a MockWebServer per host, routed by method + path, with the
 * `/api` prefix nginx strips taken off each request so fixture paths read exactly as the
 * Django route table does (`/users/profile/nav-info/`, `/api/playbooks/` for the doubled
 * family). WebSocket paths replay a [SocketScript]. Everything a client sent is kept for
 * assertions: requests, multipart parts, socket frames, and the misses that answered 404.
 *
 * Point the app at [lawrisBaseUrl] / [identityBaseUrl]; `SocketUrls.base(lawrisBaseUrl)`
 * yields this server's `ws://…/ws/` root, so production URL derivation is exercised as is.
 */
class ContractServer : AutoCloseable {
    enum class Host { LAWRIS, IDENTITY }

    data class Route(val host: Host, val method: String, val path: String)

    /** One part of a `multipart/form-data` body as the server received it. */
    data class MultipartPart(val name: String, val filename: String?, val contentType: String?, val bytes: ByteArray) {
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    val lawris = MockWebServer()
    val identity = MockWebServer()

    private val routes = ConcurrentHashMap<Route, MutableList<MockResponse>>()
    private val sockets = ConcurrentHashMap<String, SocketScript>()
    private val recorded = CopyOnWriteArrayList<Pair<Host, RecordedRequest>>()

    /** Requests nobody registered a route for, answered 404 — a failing test's first look. */
    val unrouted = CopyOnWriteArrayList<Route>()

    /** Client frames per socket path, in arrival order. */
    val socketFrames = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    /** `Client` steps whose frame did not match what arrived: path → (expected, actual). */
    val socketMismatches = CopyOnWriteArrayList<Triple<String, String, String?>>()

    val lawrisBaseUrl: String get() = lawris.url("/api/").toString()
    val identityBaseUrl: String get() = identity.url("/api/").toString()

    fun start(): ContractServer {
        lawris.dispatcher = HostDispatcher(Host.LAWRIS)
        identity.dispatcher = HostDispatcher(Host.IDENTITY)
        lawris.start()
        identity.start()
        return this
    }

    override fun close() {
        lawris.close()
        identity.close()
    }

    /** Register recorded fixtures by name; a route served twice answers in order and repeats the last. */
    fun serve(vararg names: String): ContractServer {
        names.forEach { name ->
            val fixture = Fixtures.load(name)
            serve(fixture.host.toHost(), fixture.method, fixture.path, fixture.status, fixture.body)
        }
        return this
    }

    fun serve(
        host: Host,
        method: String,
        path: String,
        status: Int = 200,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): ContractServer {
        val response = MockResponse.Builder()
            .code(status)
            .body(body)
            .addHeader("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        routes.getOrPut(Route(host, method.uppercase(), routePath(path))) { CopyOnWriteArrayList() }.add(response)
        return this
    }

    /** Replay [script] for every socket opened at [path] (`/ws/ai/lawris-ai/`). */
    fun socket(path: String, script: SocketScript): ContractServer {
        sockets[routePath(path)] = script
        return this
    }

    fun requests(host: Host = Host.LAWRIS, method: String? = null, path: String? = null): List<RecordedRequest> =
        recorded.filter { (h, request) ->
            h == host &&
                (method == null || request.method.equals(method, ignoreCase = true)) &&
                (path == null || request.routePath() == routePath(path))
        }.map { it.second }

    fun lastRequest(host: Host = Host.LAWRIS, method: String? = null, path: String? = null): RecordedRequest =
        requests(host, method, path).lastOrNull() ?: error(
            "No ${method ?: "any"} request to ${path ?: "any path"} on $host; saw " +
                recorded.map { (h, request) -> "$h ${request.method} ${request.routePath()}" },
        )

    private inner class HostDispatcher(private val host: Host) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            recorded += host to request
            val path = request.routePath()
            if (host == Host.LAWRIS && request.headers["Upgrade"].equals("websocket", ignoreCase = true)) {
                sockets[path]?.let { return MockResponse.Builder().webSocketUpgrade(Replay(path, it)).build() }
            }
            val queue = routes[Route(host, request.method.uppercase(), path)]
            if (queue.isNullOrEmpty()) {
                unrouted += Route(host, request.method.uppercase(), path)
                return MockResponse.Builder()
                    .code(404)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"detail":"ContractServer: no route for ${request.method} $path on $host"}""")
                    .build()
            }
            return if (queue.size > 1) queue.removeAt(0) else queue[0]
        }
    }

    /** Runs the script on its own thread: client frames are matched in order, with a 5 s patience each. */
    private inner class Replay(private val path: String, private val script: SocketScript) : WebSocketListener() {
        private val inbound = LinkedBlockingQueue<String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            thread(name = "contract-ws-$path", isDaemon = true) {
                for (step in script.steps) {
                    when (step) {
                        is SocketStep.Server -> {
                            webSocket.send(step.frame)
                        }

                        is SocketStep.Client -> {
                            val actual = inbound.poll(5, TimeUnit.SECONDS)
                            if (!sameFrame(step.frame, actual)) socketMismatches += Triple(path, step.frame, actual)
                        }

                        is SocketStep.Wait -> {
                            Thread.sleep(step.millis)
                        }

                        is SocketStep.Close -> {
                            webSocket.close(step.code, step.reason)
                            return@thread
                        }
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            socketFrames.getOrPut(path) { CopyOnWriteArrayList() } += text
            inbound.offer(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** A route as registered or asked about: leading slash, no query, nothing stripped (`/api/playbooks/` stays). */
        fun routePath(path: String): String {
            val p = path.substringBefore('?')
            return if (p.startsWith("/")) p else "/$p"
        }

        /** A request as it arrived: one `/api` taken off, as nginx does — `/api/users/x` → `/users/x`, `/api/api/playbooks/` → `/api/playbooks/`. */
        fun normalise(path: String): String {
            val p = routePath(path)
            return if (p.startsWith("/api/")) p.removePrefix("/api") else p
        }

        fun RecordedRequest.routePath(): String = normalise(url.encodedPath)

        private fun sameFrame(expected: String, actual: String?): Boolean {
            if (actual == null) return false
            val a = runCatching { json.parseToJsonElement(expected) }.getOrNull()
            val b = runCatching { json.parseToJsonElement(actual) }.getOrNull()
            return if (a != null && b != null) a == b else expected == actual
        }

        private fun String.toHost(): Host = when (lowercase()) {
            "vf", "ems", "identity" -> Host.IDENTITY
            else -> Host.LAWRIS
        }

        /** The parts of a `multipart/form-data` request body, in order; empty for any other body. */
        fun RecordedRequest.multipartParts(): List<MultipartPart> {
            val contentType = headers["Content-Type"] ?: return emptyList()
            val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1) ?: return emptyList()
            val raw = body?.toByteArray()?.toString(Charsets.ISO_8859_1) ?: return emptyList()
            return raw.split("--$boundary")
                .drop(1)
                .filterNot { it.trim() == "--" || it.isBlank() }
                .map { chunk ->
                    val section = chunk.removePrefix("\r\n")
                    val split = section.indexOf("\r\n\r\n")
                    val headerText = if (split >= 0) section.substring(0, split) else section
                    var content = if (split >= 0) section.substring(split + 4) else ""
                    if (content.endsWith("\r\n")) content = content.dropLast(2)
                    val headers = headerText.lines().associate { line ->
                        line.substringBefore(":").trim().lowercase() to line.substringAfter(":").trim()
                    }
                    val disposition = headers["content-disposition"] ?: ""
                    MultipartPart(
                        name = Regex("name=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1) ?: "",
                        filename = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1),
                        contentType = headers["content-type"],
                        bytes = content.toByteArray(Charsets.ISO_8859_1),
                    )
                }
        }

        /** Convenience for the common assertion: the text of the named part, or null. */
        fun List<MultipartPart>.text(name: String): String? = firstOrNull { it.name == name }?.text

        fun RecordedRequest.bodyText(): String = body?.utf8() ?: ""

        fun RecordedRequest.jsonBody() = json.parseToJsonElement(bodyText())
    }
}
