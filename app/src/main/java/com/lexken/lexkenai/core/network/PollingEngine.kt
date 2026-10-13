package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Cadence for one kind of work, per the consumer contract: start at [initialMillis],
 * multiply by [backoffFactor] after each poll up to [maxMillis], give up at [capMillis]
 * of accumulated waiting. Intervals only ever loosen — nothing may tighten them.
 */
data class PollSchedule(
    val initialMillis: Long,
    val maxMillis: Long,
    val capMillis: Long,
    val backoffFactor: Double = 1.5,
) {
    companion object {
        /** Email/chat drafting tasks: 2 s → 10 s, give up after 5 min. */
        val TASK = PollSchedule(initialMillis = 2_000, maxMillis = 10_000, capMillis = 5 * 60_000)

        /** Document-drafting runs: 5 s → 20 s, give up after 15 min. */
        val RUN = PollSchedule(initialMillis = 5_000, maxMillis = 20_000, capMillis = 15 * 60_000)

        /** Contract analysis: 5 s → 30 s, give up after 30 min. */
        val ANALYSIS = PollSchedule(initialMillis = 5_000, maxMillis = 30_000, capMillis = 30 * 60_000)

        /**
         * Legal-database search: a flat 750 ms — the web polls at 400 ms. The web gives up at
         * 45 s, but a cold tenant (statute index not yet loaded) was measured past 50 s on
         * staging while warm searches took 5–34 s, so mobile waits 90 s before calling the
         * search "unavailable".
         */
        val SEARCH = PollSchedule(initialMillis = 750, maxMillis = 750, capMillis = 90_000, backoffFactor = 1.0)

        /** Document-suite generation: the web polls every 3 s; a suite can take many minutes. */
        val GENERATION = PollSchedule(initialMillis = 3_000, maxMillis = 3_000, capMillis = 20 * 60_000, backoffFactor = 1.0)

        /** Document completion: the web polls every 2.5 s; finalisation is a short task. */
        val COMPLETION = PollSchedule(initialMillis = 2_500, maxMillis = 2_500, capMillis = 10 * 60_000, backoffFactor = 1.0)

        /** PII redaction jobs: 2.5 s between reads, fifteen minutes at most (OCR behind a queue). */
        val REDACTION = PollSchedule(initialMillis = 2_500, maxMillis = 2_500, capMillis = 15 * 60_000, backoffFactor = 1.0)

        /** A conversation re-read every 1.5 s for a minute (the web's chat poll). */
        val CHAT = PollSchedule(initialMillis = 1_500, maxMillis = 1_500, capMillis = 60_000, backoffFactor = 1.0)
    }
}

sealed interface PollEvent<out T> {
    /** A snapshot arrived; [data] is the latest server state. Emitted for every successful poll. */
    data class Snapshot<T>(val data: T) : PollEvent<T>

    /** [data] satisfied the terminal predicate — the flow completes after this. */
    data class Terminal<T>(val data: T) : PollEvent<T>

    /** A poll failed with something retryable; polling continues. Surfaced so the UI can hint. */
    data class Stumble(val error: DataError) : PollEvent<Nothing>

    /** Credit/plan/session errors are terminal by contract — the flow completes after this. */
    data class Failed(val error: DataError) : PollEvent<Nothing>

    /** The wall-clock cap elapsed with no terminal state. Emitted once; the flow completes. */
    data object Overdue : PollEvent<Nothing>
}

/**
 * The one polling loop for all consumer-API work. Snapshots survive restarts on both
 * sides, so a network error backs off and resumes; only the documented terminal
 * conditions stop the loop.
 */
class PollingEngine {

    fun <T> poll(
        schedule: PollSchedule,
        isTerminal: (T) -> Boolean,
        fetch: suspend () -> Result<T, DataError>,
    ): Flow<PollEvent<T>> = flow {
        var interval = schedule.initialMillis
        var waited = 0L
        while (true) {
            when (val result = fetch()) {
                is Result.Success -> {
                    if (isTerminal(result.data)) {
                        emit(PollEvent.Terminal(result.data))
                        return@flow
                    }
                    emit(PollEvent.Snapshot(result.data))
                }
                is Result.Failure -> when (result.error) {
                    is DataError.Api -> {
                        emit(PollEvent.Failed(result.error))
                        return@flow
                    }
                    else -> emit(PollEvent.Stumble(result.error))
                }
            }
            if (waited >= schedule.capMillis) {
                emit(PollEvent.Overdue)
                return@flow
            }
            delay(interval)
            waited += interval
            interval = (interval * schedule.backoffFactor).toLong().coerceAtMost(schedule.maxMillis)
        }
    }
}
