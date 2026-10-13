package com.lexken.lexkenai.core.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * One recorded endpoint response, captured from the shape the web client receives.
 *
 * File format (`src/test/resources/fixtures/<name>.json`):
 * ```
 * { "host": "lawris" | "vf" | "ems",       // which server answers it (default lawris)
 *   "method": "GET", "path": "/sub/current/",
 *   "status": 200,
 *   "body": { ... } | "raw string" }
 * ```
 * [body] is returned verbatim when it is a JSON string, otherwise re-serialised compactly.
 */
data class Fixture(
    val name: String,
    val host: String,
    val method: String,
    val path: String,
    val status: Int,
    val body: String,
)

object Fixtures {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, Fixture>()

    /** Load `fixtures/<name>.json` from the classpath. Throws when the file is missing. */
    fun load(name: String): Fixture = synchronized(cache) {
        cache.getOrPut(name) { parse(name, read(name)) }
    }

    /** The response body only — the common case. */
    fun body(name: String): String = load(name).body

    private fun read(name: String): String {
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$name.json")
            ?: Fixtures::class.java.getResourceAsStream("/fixtures/$name.json")
            ?: throw IllegalArgumentException("No fixture named '$name' (expected fixtures/$name.json on the classpath)")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun parse(name: String, text: String): Fixture {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("Fixture '$name' is not a JSON object")
        fun string(key: String): String = root[key]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Fixture '$name' is missing \"$key\"")
        val bodyElement = root["body"] ?: throw IllegalArgumentException("Fixture '$name' is missing \"body\"")
        val body = if (bodyElement is JsonPrimitive && bodyElement.isString) bodyElement.content else bodyElement.toString()
        val path = string("path")
        require(path.startsWith("/")) { "Fixture '$name' path must start with '/'" }
        return Fixture(
            name = name,
            host = root["host"]?.jsonPrimitive?.contentOrNull ?: "lawris",
            method = string("method").uppercase(),
            path = path,
            status = root["status"]?.jsonPrimitive?.int ?: 200,
            body = body,
        )
    }
}
