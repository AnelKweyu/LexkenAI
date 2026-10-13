package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import kotlin.coroutines.coroutineContext

/**
 * The backend's machine codes for 401/403 bodies. The set is open — anything unrecognised
 * falls back to the plain status mapping so new codes never crash a shipped client.
 */
internal object ApiErrorCodes {
    val insufficientCredits = setOf("INSUFFICIENT_AI_CREDITS", "INSUFFICIENT_AI_CREDITS_VIEW")
    val noSubscription = setOf("NO_VALID_SUBSCRIPTION", "SUBSCRIPTION_REQUIRED")
    val planRestricted = setOf("AI_ACCESS_DENIED", "AI_ACCESS_DENIED_VIEW", "AI_PLAN_RESTRICTION")
    val sessionChallenge = setOf("ai_session_required", "turnstile_required")
}

/**
 * Like [safeCall] but reads the machine code out of a 401/403 body, so credit, plan and
 * session failures surface as [DataError.Api] instead of a bare FORBIDDEN.
 */
suspend inline fun <T> safeApiCall(json: Json, crossinline block: suspend () -> T): Result<T, DataError> = try {
    Result.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    coroutineContext.ensureActive()
    Result.Failure(e.toApiError(json))
}

fun Throwable.toApiError(json: Json): DataError {
    val http = this as? HttpException ?: return toNetworkError()
    // A bare 402 is the credits wall by HTTP semantics, whatever the body says.
    if (http.code() == 402) return DataError.Api.INSUFFICIENT_CREDITS
    if (http.code() == 400) return http.validationError(json) ?: toNetworkError()
    if (http.code() != 401 && http.code() != 403) return toNetworkError()
    val code = http.errorCode(json) ?: return toNetworkError()
    return when (code) {
        in ApiErrorCodes.insufficientCredits -> DataError.Api.INSUFFICIENT_CREDITS
        in ApiErrorCodes.noSubscription -> DataError.Api.NO_SUBSCRIPTION
        in ApiErrorCodes.planRestricted -> DataError.Api.PLAN_RESTRICTED
        in ApiErrorCodes.sessionChallenge -> DataError.Api.SESSION_CHALLENGE
        else -> toNetworkError()
    }
}

/** `error_code` and `code` are both in use; the body may not be JSON at all. */
fun HttpException.errorCode(json: Json): String? {
    val body = response()?.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        (obj["error_code"] ?: obj["code"])?.jsonPrimitive?.content
    }.getOrNull()
}

/**
 * The web's one listed 400 (`httpService.jsx`): `error == "Validation Error"` with `details`
 * as `{field: [messages] | message}` or a single string. Anything else is null — the caller
 * matches its own body.
 */
fun HttpException.validationError(json: Json): DataError.Validation? {
    val body = response()?.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null
    return parseValidationError(json, body)
}

fun parseValidationError(json: Json, body: String): DataError.Validation? = runCatching {
    val obj = json.parseToJsonElement(body).jsonObject
    if (obj["error"]?.jsonPrimitive?.contentOrNull != VALIDATION_ERROR) return null
    val fields = when (val details = obj["details"]) {
        null, is JsonNull -> emptyMap()
        is JsonObject -> details.mapValues { (_, value) -> value.messages() }
        else -> mapOf(DataError.Validation.NON_FIELD to details.messages())
    }
    DataError.Validation(fields, message = obj["message"]?.jsonPrimitive?.contentOrNull)
}.getOrNull()

private const val VALIDATION_ERROR = "Validation Error"

private fun JsonElement.messages(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    else -> listOf(toString())
}
