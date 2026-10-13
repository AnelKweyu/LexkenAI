package com.lexken.lexkenai.core.network.aisession

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The web's AI-session rule set (`services/httpService.jsx`), kept as pure functions so every
 * clause has a test of its own. `security/permissions.py` `AISessionRequired` guards the four
 * prefixes; the session is minted at `ai/session/` and attached as `X-AI-Session` +
 * `X-Device-ID`. The mint sits behind a Cloudflare Turnstile the app cannot pass (backend
 * ask #1), so the interceptor built on these rules degrades to "send the request anyway" and
 * lets the route's 403 surface as `DataError.Api.SESSION_CHALLENGE` until a native mint lands.
 */
object AiSessionRules {
    /** `AI_SESSION_PROTECTED_PATHS` (httpService.jsx:64-73), matched by prefix after normalising. */
    val PROTECTED_PREFIXES: List<String> = listOf(
        "/ai/lawris-ai/",
        "/ai/generate-documents/",
        "/ai/submission-analyzer/",
        "/ai/contractual-analysis/",
    )

    /** `MAX_AI_SESSION_RETRIES` — re-mint and replay this many times per request on a challenge. */
    const val MAX_RETRIES = 3

    /** `VITE_AI_SESSION_TTL_FALLBACK_MS` — when the mint answers without `expires_in`. */
    const val FALLBACK_TTL_MILLIS = 10 * 60_000L

    /** The web stores `expiresAt = now + expires_in × 1000 − 5 s` so a session is never used at its edge. */
    const val EXPIRY_SKEW_MILLIS = 5_000L

    /** `code` / `error_code` / `detail.code` values that mean "mint (again) and replay". */
    val CHALLENGE_CODES: Set<String> = setOf("ai_session_required", "turnstile_required")

    private val CHALLENGE_PHRASES = listOf("turnstile", "ai session", "authentication failed")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** `/api/ai/lawris-ai`, `ai/lawris-ai/` and `/ai/lawris-ai/?x=1` all protect; `/ai/legal-options/` does not. */
    fun isProtected(path: String): Boolean {
        val normalised = normalise(path)
        return PROTECTED_PREFIXES.any { normalised.startsWith(it) }
    }

    /** Leading slash, one `/api` prefix dropped (the tenant base already carries it), trailing slash, no query. */
    fun normalise(path: String): String {
        var p = path.substringBefore('?').substringBefore('#')
        if (!p.startsWith("/")) p = "/$p"
        if (p == "/api" || p.startsWith("/api/")) p = p.removePrefix("/api").ifEmpty { "/" }
        if (!p.endsWith("/")) p += "/"
        return p
    }

    /**
     * httpService.jsx:133-149 — a 401/403 whose `code`, `error_code` or `detail.code` is a
     * challenge code, or whose `message` / `detail` / `error` mentions Turnstile, the AI
     * session or "authentication failed". Anything else (a plain 401, a credits 403) is not.
     */
    fun isChallenge(status: Int, body: String?): Boolean {
        if (status != 401 && status != 403) return false
        val text = body?.takeIf { it.isNotBlank() } ?: return false
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        val codes = listOfNotNull(
            obj.string("code"),
            obj.string("error_code"),
            (obj["detail"] as? JsonObject)?.string("code"),
        )
        if (codes.any { it in CHALLENGE_CODES }) return true
        val messages = listOfNotNull(obj.string("message"), obj.string("detail"), obj.string("error"))
        return messages.any { message -> CHALLENGE_PHRASES.any { phrase -> message.contains(phrase, ignoreCase = true) } }
    }

    /** The mint's own retry pacing: `300 ms × attempt` (attempt is 1-based). */
    fun mintRetryDelayMillis(attempt: Int): Long = 300L * attempt

    fun expiresAt(nowMillis: Long, expiresInSeconds: Long?): Long =
        nowMillis + (expiresInSeconds?.takeIf { it > 0 }?.times(1_000) ?: FALLBACK_TTL_MILLIS) - EXPIRY_SKEW_MILLIS

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
}
