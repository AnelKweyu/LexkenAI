package com.lexken.lexkenai.core.network.aisession

import com.lexken.lexkenai.core.auth.TokenStore
import com.lexken.lexkenai.core.common.Clock
import com.lexken.lexkenai.core.network.safeCall
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * `ai/session/` (`ai/views.py` `AISessionInitView`): `{device_id}` with the bearer and
 * `X-Device-ID`; answers `{ai_session, expires_in, code: "ai_session_created"}`. The web sends
 * it from an axios instance with no interceptors, so here it lives on the plain client with an
 * explicit bearer. Behind `TurnstileRequired` for every principal today (backend ask #1).
 */
interface AiSessionApi {
    @POST("ai/session/")
    suspend fun mint(
        @Header("Authorization") bearer: String,
        @Header("X-Device-ID") deviceId: String,
        @Body body: AiSessionRequest,
    ): AiSessionResponse
}

@Serializable
data class AiSessionRequest(
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class AiSessionResponse(
    @SerialName("ai_session") val aiSession: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
    val code: String? = null,
)

/** A minted session and the instant (per [Clock]) after which it is no longer sent. */
data class AiSessionToken(val value: String, val expiresAtMillis: Long)

/** Mints a session for [deviceId], or null when the host will not issue one. */
fun interface AiSessionMinter {
    suspend fun mint(deviceId: String): AiSessionToken?
}

/**
 * `createAiSessionWithRetry` (httpService.jsx:184-220): up to [AiSessionRules.MAX_RETRIES]
 * attempts, `300 ms × attempt` apart, a body without `ai_session` counting as a failed attempt.
 * One deliberate difference: a 401/403 ends the loop at once — that is the Turnstile gate
 * refusing a mobile principal, and three refusals per request would only triple the noise.
 */
class RetrofitAiSessionMinter(
    private val api: AiSessionApi,
    private val tokenStore: TokenStore,
    private val clock: Clock,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : AiSessionMinter {
    override suspend fun mint(deviceId: String): AiSessionToken? {
        val bearer = tokenStore.current()?.accessToken ?: return null
        for (attempt in 1..AiSessionRules.MAX_RETRIES) {
            when (val minted = safeCall { api.mint("Bearer $bearer", deviceId, AiSessionRequest(deviceId)) }) {
                is Result.Success -> {
                    val session = minted.data.aiSession?.takeIf { it.isNotBlank() }
                    if (session != null) {
                        return AiSessionToken(session, AiSessionRules.expiresAt(clock.nowMillis(), minted.data.expiresInSeconds))
                    }
                }

                is Result.Failure -> {
                    when (minted.error) {
                        DataError.Network.UNAUTHORIZED, DataError.Network.FORBIDDEN -> return null
                        else -> Unit
                    }
                }
            }
            if (attempt < AiSessionRules.MAX_RETRIES) pause(AiSessionRules.mintRetryDelayMillis(attempt))
        }
        return null
    }
}
