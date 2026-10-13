package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The poll is the record and the socket is an accelerator: live frames interleave with
 * snapshots, the poll's terminal read ends the job and takes the socket down, and a socket
 * that closes early, never connects or fails outright leaves the poll untouched.
 */
class JobTrackerTest {
    private data class Status(val value: String)

    private val schedule = PollSchedule(initialMillis = 1_000, maxMillis = 1_000, capMillis = 60_000, backoffFactor = 1.0)

    /** A scripted socket: emits [events] with [gapMillis] between them, then completes (or throws). */
    private class ScriptedSocket(
        private val events: List<SocketEvent>,
        private val gapMillis: Long = 500,
        private val failWith: Throwable? = null,
    ) : SocketClient {
        var cancelled = false
            private set
        var connections = 0
            private set
        var lastUrl: String? = null
            private set
        var lastOpenFrames: List<String> = emptyList()
            private set

        override fun connect(url: String, openFrames: List<String>, policy: ReconnectPolicy): Flow<SocketEvent> = flow {
            connections++
            lastUrl = url
            lastOpenFrames = openFrames
            events.forEach {
                emit(it)
                delay(gapMillis)
            }
            failWith?.let { throw it }
            // Stay open until the collector goes away, like a quiet server.
            delay(Long.MAX_VALUE / 2)
        }.onCompletion { cause -> if (cause != null) cancelled = true }
    }

    private fun script(vararg results: Result<Status, DataError>): suspend () -> Result<Status, DataError> {
        val queue = ArrayDeque(results.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun `live frames interleave with snapshots and the poll's terminal read ends the job and the socket`() = runTest {
        val socket = ScriptedSocket(
            listOf(SocketEvent.Connected(0), SocketEvent.Frame("""{"type":"stream.chunk","message":"Dear"}""")),
            gapMillis = 400,
        )
        val tracker = JobTracker(PollingEngine(), socket)

        val events = tracker.track(
            schedule = schedule,
            isTerminal = { it.value == "SUCCESS" },
            socket = SocketSubscription.chat("https://stage.lexken.ai/api/", "t-1"),
            fetch = script(Result.Success(Status("PENDING")), Result.Success(Status("SUCCESS"))),
        ).toList()

        assertThat(events).containsExactly(
            JobEvent.Snapshot(Status("PENDING")),
            JobEvent.LiveConnected(0),
            JobEvent.Live("""{"type":"stream.chunk","message":"Dear"}"""),
            JobEvent.Terminal(Status("SUCCESS")),
        )
        assertThat(socket.cancelled).isTrue()
        assertThat(socket.lastUrl).isEqualTo("wss://stage.lexken.ai/ws/ai/lawris-ai/")
        assertThat(socket.lastOpenFrames).containsExactly("""{"thread_id":"t-1"}""")
    }

    @Test
    fun `a socket that closes early never ends the job`() = runTest {
        val socket = ScriptedSocket(listOf(SocketEvent.Connected(0), SocketEvent.Closed(1000, "done")), gapMillis = 100)
        val tracker = JobTracker(PollingEngine(), socket)

        val events = tracker.track(
            schedule = schedule,
            isTerminal = { it.value == "SUCCESS" },
            socket = SocketSubscription("wss://x/ws/y/"),
            fetch = script(
                Result.Success(Status("PENDING")),
                Result.Success(Status("PROGRESS")),
                Result.Success(Status("SUCCESS")),
            ),
        ).toList()

        assertThat(events.filterIsInstance<JobEvent.Snapshot<Status>>().size).isEqualTo(2)
        assertThat(events.last()).isEqualTo(JobEvent.Terminal(Status("SUCCESS")))
        assertThat(events.contains(JobEvent.LiveClosed(1000, "done"))).isTrue()
    }

    @Test
    fun `a socket failure is swallowed and the poll still reaches its terminal read`() = runTest {
        val socket = ScriptedSocket(emptyList(), failWith = IllegalStateException("upgrade refused"))
        val tracker = JobTracker(PollingEngine(), socket)

        val events = tracker.track(
            schedule = schedule,
            isTerminal = { it.value == "SUCCESS" },
            socket = SocketSubscription("wss://x/ws/y/"),
            fetch = script(Result.Success(Status("PENDING")), Result.Success(Status("SUCCESS"))),
        ).toList()

        assertThat(events).containsExactly(JobEvent.Snapshot(Status("PENDING")), JobEvent.Terminal(Status("SUCCESS")))
    }

    @Test
    fun `without a subscription no socket is opened and typed api failures end the job`() = runTest {
        val socket = ScriptedSocket(listOf(SocketEvent.Connected(0)))
        val tracker = JobTracker(PollingEngine(), socket)

        val events = tracker.track(
            schedule = schedule,
            isTerminal = { it.value == "SUCCESS" },
            fetch = script(Result.Failure(DataError.Api.SESSION_CHALLENGE)),
        ).toList()

        assertThat(events).containsExactly(JobEvent.Failed(DataError.Api.SESSION_CHALLENGE))
        assertThat(socket.connections).isEqualTo(0)
    }
}
