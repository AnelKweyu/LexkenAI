package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Virtual-time coverage of the cadence and terminal rules. */
class PollingEngineTest {
    private val engine = PollingEngine()
    private val schedule = PollSchedule(initialMillis = 2_000, maxMillis = 10_000, capMillis = 60_000)

    private data class Status(val value: String)

    private fun script(vararg results: Result<Status, DataError>): suspend () -> Result<Status, DataError> {
        val queue = ArrayDeque(results.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun `polls until terminal and emits every snapshot`() = runTest {
        val events = engine.poll(
            schedule = schedule,
            isTerminal = { it.value == "SUCCESS" },
            fetch = script(
                Result.Success(Status("PENDING")),
                Result.Success(Status("PROGRESS")),
                Result.Success(Status("SUCCESS")),
            ),
        ).toList()

        assertThat(events).containsExactly(
            PollEvent.Snapshot(Status("PENDING")),
            PollEvent.Snapshot(Status("PROGRESS")),
            PollEvent.Terminal(Status("SUCCESS")),
        )
    }

    @Test
    fun `intervals back off and never tighten`() = runTest {
        val results = List(5) { Result.Success(Status("PROGRESS")) } + Result.Success(Status("SUCCESS"))
        engine.poll(schedule, { it.value == "SUCCESS" }, script(*results.toTypedArray())).toList()

        assertThat(currentTime).isEqualTo(2_000L + 3_000 + 4_500 + 6_750 + 10_000)
    }

    @Test
    fun `network errors are stumbles and polling continues`() = runTest {
        val events = engine.poll(
            schedule = schedule,
            isTerminal = { it.value == "SUCCESS" },
            fetch = script(
                Result.Failure(DataError.Network.NO_INTERNET),
                Result.Success(Status("SUCCESS")),
            ),
        ).toList()

        assertThat(events).containsExactly(
            PollEvent.Stumble(DataError.Network.NO_INTERNET),
            PollEvent.Terminal(Status("SUCCESS")),
        )
    }

    @Test
    fun `credit and plan errors are terminal`() = runTest {
        val events = engine.poll(
            schedule = schedule,
            isTerminal = { it.value == "SUCCESS" },
            fetch = script(Result.Failure(DataError.Api.INSUFFICIENT_CREDITS)),
        ).toList()

        assertThat(events).containsExactly(PollEvent.Failed(DataError.Api.INSUFFICIENT_CREDITS))
    }

    @Test
    fun `wall-clock cap emits Overdue exactly once and stops`() = runTest {
        val tight = PollSchedule(initialMillis = 1_000, maxMillis = 1_000, capMillis = 3_000, backoffFactor = 1.0)
        val events = engine.poll(
            schedule = tight,
            isTerminal = { false },
            fetch = { Result.Success(Status("PROGRESS")) },
        ).toList()

        assertThat(events.count { it == PollEvent.Overdue }).isEqualTo(1)
        assertThat(events.last()).isInstanceOf(PollEvent.Overdue::class)
    }
}
