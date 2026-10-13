package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One event stream for a piece of server-side work. The poll events are the record; the
 * `Live*` events come from the job's WebSocket and are advisory — they may advance the UI
 * earlier (see [StreamOverPollRules]) but never end the job, and a socket that fails, closes
 * or never emits leaves the poll untouched.
 */
sealed interface JobEvent<out T> {
    data class Snapshot<T>(val data: T) : JobEvent<T>
    data class Terminal<T>(val data: T) : JobEvent<T>
    data class Stumble(val error: DataError) : JobEvent<Nothing>
    data class Failed(val error: DataError) : JobEvent<Nothing>
    data object Overdue : JobEvent<Nothing>

    /** The job's socket connected; [attempt] is 0 for the first connect, then the reconnect count. */
    data class LiveConnected(val attempt: Int) : JobEvent<Nothing>
    /** A server text frame, unparsed. */
    data class Live(val frame: String) : JobEvent<Nothing>
    data class LiveClosed(val code: Int?, val reason: String?) : JobEvent<Nothing>
}

/**
 * The socket a job may listen on while it polls. [openFrames] are sent after every (re)connect.
 */
data class SocketSubscription(
    val url: String,
    val openFrames: List<String> = emptyList(),
    val policy: ReconnectPolicy = ReconnectPolicy(),
) {
    companion object {
        /**
         * The chat consumer (`ws/ai/lawris-ai/`) reads `thread_id` off every text frame it
         * receives, so the join frame carries it and nothing else is ever sent as JSON.
         */
        fun chat(apiBaseUrl: String, threadId: String) =
            SocketSubscription(url = SocketUrls.chat(apiBaseUrl), openFrames = listOf(chatJoinFrame(threadId)))

        fun chatJoinFrame(threadId: String): String = buildJsonObject { put("thread_id", threadId) }.toString()
    }
}

/**
 * The backend's execution style: a view answers 202 with an id, a worker does the work, the
 * REST read is the source of truth and the socket streams progress. [track] runs the poll and,
 * when a [SocketSubscription] is given, the socket beside it; the poll's completion ends the
 * flow and takes the socket down with it.
 */
class JobTracker(
    private val polling: PollingEngine,
    private val sockets: SocketClient,
) {
    fun <T> track(
        schedule: PollSchedule,
        isTerminal: (T) -> Boolean,
        socket: SocketSubscription? = null,
        fetch: suspend () -> Result<T, DataError>,
    ): Flow<JobEvent<T>> = channelFlow {
        val live = socket?.let { subscription ->
            launch {
                sockets.connect(subscription.url, subscription.openFrames, subscription.policy)
                    .catch { /* the socket is an accelerator; its failure is not the job's */ }
                    .collect { event ->
                        send(
                            when (event) {
                                is SocketEvent.Connected -> JobEvent.LiveConnected(event.attempt)
                                is SocketEvent.Frame -> JobEvent.Live(event.text)
                                is SocketEvent.Closed -> JobEvent.LiveClosed(event.code, event.reason)
                            },
                        )
                    }
            }
        }
        polling.poll(schedule, isTerminal, fetch).collect { event ->
            send(
                when (event) {
                    is PollEvent.Snapshot -> JobEvent.Snapshot(event.data)
                    is PollEvent.Terminal -> JobEvent.Terminal(event.data)
                    is PollEvent.Stumble -> JobEvent.Stumble(event.error)
                    is PollEvent.Failed -> JobEvent.Failed(event.error)
                    PollEvent.Overdue -> JobEvent.Overdue
                },
            )
        }
        live?.cancel()
    }
}
