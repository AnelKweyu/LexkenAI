package com.lexken.lexkenai.core.testing

import com.lexken.lexkenai.core.network.ReconnectPolicy
import com.lexken.lexkenai.core.network.SocketClient
import com.lexken.lexkenai.core.network.SocketEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A `SocketClient` double for use-case and flow tests: records every `connect` (URL, open
 * frames, policy), plays the events scripted for the path, then stays open and relays whatever
 * the test [push]es — so a flow can be driven frame by frame without a server.
 */
class RecordingSocketClient : SocketClient {
    data class Connection(val url: String, val openFrames: List<String>, val policy: ReconnectPolicy) {
        val path: String get() = url.substringAfter("://").substringAfter("/").let { "/$it" }.substringBefore('?')
    }

    val connections = CopyOnWriteArrayList<Connection>()

    /** Collectors that have gone away, by path — a job that ended should have taken its socket down. */
    val cancelled = CopyOnWriteArrayList<String>()

    private val scripts = HashMap<String, List<SocketEvent>>()
    private val live = MutableSharedFlow<Pair<String, SocketEvent>>(extraBufferCapacity = 64)

    /** Events to emit as soon as a socket at [path] connects (default: `Connected(0)` only). */
    fun script(path: String, vararg events: SocketEvent) {
        scripts[path] = events.toList()
    }

    /** Deliver [event] to every open collector of [path]. */
    fun push(path: String, event: SocketEvent) {
        check(live.tryEmit(path to event)) { "RecordingSocketClient buffer full" }
    }

    fun frame(path: String, text: String) = push(path, SocketEvent.Frame(text))

    override fun connect(url: String, openFrames: List<String>, policy: ReconnectPolicy): Flow<SocketEvent> {
        val connection = Connection(url, openFrames, policy)
        return flow {
            connections += connection
            (scripts[connection.path] ?: listOf(SocketEvent.Connected(0))).forEach { emit(it) }
            live.collect { (path, event) -> if (path == connection.path) emit(event) }
        }.onCompletion { cancelled += connection.path }
    }
}
