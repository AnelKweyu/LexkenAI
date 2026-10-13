package com.lexken.lexkenai.core.auth

import com.lexken.lexkenai.core.network.toNetworkError
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Error
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Fresh per request — the identity host's replay guard. A reused or stale nonce is refused,
 * and the refusal is masked as HTTP 404, so never cache one.
 */
internal fun nonce(): String = "and-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

/**
 * The parsed body of a non-2xx answer. The two hosts speak different dialects: Lawris answers
 * `{"error": …, "error_code": …}` or `{"message": …}`, the identity host answers DRF's
 * `{"detail": …}` (sometimes with boolean markers beside it, e.g.
 * `email_verification_required`), field-keyed arrays (`{"email": ["…already exists."]}`,
 * `{"non_field_errors": […]}`), its problem payload `{code, detail, status, title}` and RFC 6749's
 * `{"error": "invalid_grant", "error_description": …}`. `safeCall` flattens all of them to
 * `UNKNOWN`, so the auth calls go through [httpCall], which keeps the body next to the mapped
 * network error.
 */
internal class ErrorBody(
    val code: String?,
    val error: String?,
    /** RFC 6749 section 5.2 `error_description` — the sentence that goes with an OAuth `error` code. */
    val errorDescription: String?,
    val message: String?,
    val detail: String?,
    /** Field → its first message, for serializer rejections (DRF's `non_field_errors` included). */
    val fieldErrors: Map<String, String>,
    /** Boolean markers the host sets next to `detail`, e.g. `email_verification_required`. */
    val flags: Set<String>,
) {
    fun mentions(needle: String): Boolean =
        listOf(error, errorDescription, message, detail).any { it?.contains(needle, ignoreCase = true) == true } ||
            fieldErrors.values.any { it.contains(needle, ignoreCase = true) }

    fun field(name: String): String? = fieldErrors.entries.firstOrNull { it.key.contains(name, ignoreCase = true) }?.value

    /** `true` when the body carries `"<name>": true`. */
    fun flag(name: String): Boolean = name in flags

    /**
     * The first sentence the host offered, for showing verbatim: DRF's `detail`, Lawris's
     * `message`, the OAuth description, then `non_field_errors`, then the first field message.
     * A bare OAuth code (`invalid_grant`) is last — it is not a sentence.
     */
    val firstSentence: String?
        get() = detail ?: message ?: errorDescription
            ?: fieldErrors[NON_FIELD_ERRORS] ?: fieldErrors.values.firstOrNull() ?: error

    companion object {
        const val NON_FIELD_ERRORS = "non_field_errors"
    }
}

internal class HttpFailure(val network: DataError.Network, val body: ErrorBody?) : Error {
    fun mentions(needle: String): Boolean = body?.mentions(needle) == true
}

internal suspend fun <T> httpCall(json: Json, block: suspend () -> T): Result<T, HttpFailure> = try {
    Result.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    coroutineContext.ensureActive()
    Result.Failure(HttpFailure(e.toNetworkError(), e.errorBody(json)))
}

private fun Throwable.errorBody(json: Json): ErrorBody? {
    val http = this as? HttpException ?: return null
    val text = runCatching { http.response()?.errorBody()?.string() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: return null
    return parseErrorBody(json, text)
}

/** Exposed for the parser's own tests; callers go through [httpCall]. */
internal fun parseErrorBody(json: Json, text: String): ErrorBody? {
    // Django's own 5xx page and Cloudflare's challenge are HTML — nothing to read.
    if (text.trimStart().startsWith("<")) return null
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    fun key(name: String): String? = runCatching {
        obj[name]?.jsonPrimitive?.takeIf { it.isString }?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
    val fieldErrors = obj.mapNotNull { (name, value) ->
        runCatching { value.jsonArray.firstOrNull()?.jsonPrimitive?.content }.getOrNull()?.let { name to it }
    }.toMap()
    val flags = obj.mapNotNull { (name, value) ->
        name.takeIf { runCatching { value.jsonPrimitive.booleanOrNull }.getOrNull() == true }
    }.toSet()
    return ErrorBody(
        code = key("error_code") ?: key("code"),
        error = key("error"),
        errorDescription = key("error_description"),
        message = key("message"),
        detail = key("detail"),
        fieldErrors = fieldErrors,
        flags = flags,
    )
}
