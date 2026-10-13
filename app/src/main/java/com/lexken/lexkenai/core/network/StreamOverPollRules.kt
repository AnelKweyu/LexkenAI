package com.lexken.lexkenai.core.network

/**
 * How a socket frame and the poll twin share one screen: the poll keeps running at its cadence
 * and is the source of truth; a frame only advances the UI earlier. A frame never moves state
 * backwards, and only the poll's terminal read ends the work — except a typed credit or
 * subscription refusal, which is terminal by contract on either rail.
 */
object StreamOverPollRules {
    /** The bar is monotonic on both rails: the larger of what the poll and the frames said. */
    fun percent(current: Int?, incoming: Int?): Int? = when {
        incoming == null -> current
        current == null -> incoming.coerceIn(0, 100)
        else -> maxOf(current, incoming.coerceIn(0, 100))
    }

    /** A stage never regresses: the furthest one seen (by enum order) wins. */
    fun <T : Enum<T>> stage(current: T?, incoming: T?): T? = when {
        incoming == null -> current
        current == null -> incoming
        incoming.ordinal >= current.ordinal -> incoming
        else -> current
    }

    /** A status line is worth showing when it says something new. */
    fun labelChanged(last: String?, incoming: String?): Boolean = !incoming.isNullOrBlank() && incoming != last

    /** Streamed answer text: a chunk appends, a full response replaces. */
    fun text(current: String, chunk: String?, full: String?): String = when {
        full != null -> full
        chunk != null -> current + chunk
        else -> current
    }
}
