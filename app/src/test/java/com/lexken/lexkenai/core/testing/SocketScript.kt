package com.lexken.lexkenai.core.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One recorded WebSocket conversation, replayed by [ContractServer]. Each step is what the
 * server does next: send a frame, wait for a client frame and check it, pause, or close.
 */
sealed interface SocketStep {
    /** A frame the server sends. */
    data class Server(val frame: String) : SocketStep

    /** A frame the client must send next; compared as JSON when both sides parse, else as text. */
    data class Client(val frame: String) : SocketStep

    data class Wait(val millis: Long) : SocketStep

    data class Close(val code: Int = 1000, val reason: String = "done") : SocketStep
}

data class SocketScript(val name: String, val steps: List<SocketStep>) {
    companion object {
        /** The server just streams [frames] and stays open. */
        fun stream(vararg frames: String): SocketScript = SocketScript("inline", frames.map { SocketStep.Server(it) })

        /**
         * `fixtures/sockets/<name>.jsonl`: one JSON object per line, holding exactly one of
         * `server` (object or string), `client` (object or string), `close` (int, with optional
         * `reason`) or `wait_ms`. Blank lines and `#` comments are skipped.
         */
        fun parse(name: String, text: String): SocketScript {
            val json = Json { ignoreUnknownKeys = true }
            val steps = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapIndexed { index, line ->
                    val obj = runCatching { json.parseToJsonElement(line) as JsonObject }
                        .getOrElse { throw IllegalArgumentException("Socket fixture '$name' line ${index + 1} is not a JSON object: $line") }
                    fun frame(key: String): String {
                        val element = obj.getValue(key)
                        return if (element is JsonPrimitive && element.isString) element.content else element.toString()
                    }
                    when {
                        "server" in obj -> SocketStep.Server(frame("server"))

                        "client" in obj -> SocketStep.Client(frame("client"))

                        "close" in obj -> SocketStep.Close(
                            code = obj.getValue("close").jsonPrimitive.int,
                            reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "done",
                        )

                        "wait_ms" in obj -> SocketStep.Wait(obj.getValue("wait_ms").jsonPrimitive.long)

                        else -> throw IllegalArgumentException("Socket fixture '$name' line ${index + 1} has no server/client/close/wait_ms: $line")
                    }
                }
                .toList()
            return SocketScript(name, steps)
        }
    }
}
