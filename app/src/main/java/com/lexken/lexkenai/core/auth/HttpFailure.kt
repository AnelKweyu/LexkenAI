package com.lexken.lexkenai.core.auth

import com.lexken.lexkenai.core.network.toNetworkError
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Error
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
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
 * `{"detail": …}` or field-keyed arrays (`{"email": ["…already exists."]}`) and RFC 6749's
 * `{"error": "invalid_grant"}`. `safeCall` flattens all of them to `UNKNOWN`, so the auth calls
 * go through [httpCall], which keeps the body next to the mapped network error.
 */
internal class ErrorBody(
    val code: String?,
    val error: String?,
    val message: String?,
    val detail: String?,
    /** Field → its first message, for serializer rejections. */
    val fieldErrors: Map<String, String>,
) {
    fun mentions(needle: String): Boolean =
        listOf(error, message, detail).any { it?.contains(needle, ignoreCase = true) == true } ||
            fieldErrors.values.any { it.contains(needle, ignoreCase = true) }

    fun field(name: String): String? = fieldErrors.entries.firstOrNull { it.key.contains(name, ignoreCase = true) }?.value

    /** The first sentence the host offered, for showing verbatim. */
    val firstSentence: String? get() = detail ?: message ?: error ?: fieldErrors.values.firstOrNull()
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
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    fun key(name: String): String? = runCatching { obj[name]?.jsonPrimitive?.content }.getOrNull()
    val fieldErrors = obj.mapNotNull { (name, value) ->
        runCatching { value.jsonArray.firstOrNull()?.jsonPrimitive?.content }.getOrNull()?.let { name to it }
    }.toMap()
    return ErrorBody(
        code = key("error_code") ?: key("code"),
        error = key("error"),
        message = key("message"),
        detail = key("detail"),
        fieldErrors = fieldErrors,
    )
}
