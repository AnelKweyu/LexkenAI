package com.lexken.lexkenai.core.network.aisession

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * The web's request + response interceptors for AI-session-protected paths
 * (httpService.jsx:246-254, 329-422, 513-606), as one OkHttp application interceptor:
 *
 * 1. a request under a protected prefix gets `X-Device-ID` and, when the cache can mint one,
 *    `X-AI-Session`; any `X-Turnstile-Token` is stripped;
 * 2. a 401/403 that [AiSessionRules.isChallenge] recognises re-mints and replays, up to
 *    [AiSessionRules.MAX_RETRIES] times, then the last response is handed back as is;
 * 3. a refused mint (the Turnstile gate) sends the request without a session and never loops:
 *    the route's own 403 `ai_session_required` becomes `DataError.Api.SESSION_CHALLENGE`.
 *
 * Sits after `AuthInterceptor` on the authed client; the mint itself runs on the plain client.
 */
class AiSessionInterceptor(
    private val cache: AiSessionCache,
    private val deviceIds: DeviceIdStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!AiSessionRules.isProtected(request.url.encodedPath)) return chain.proceed(request)

        val deviceId = runBlocking { deviceIds.getOrCreate() }
        var session = runBlocking { cache.current(deviceId) }
        var response = chain.proceed(request.withSession(session, deviceId))
        var replays = 0
        // A request that went out without a session was refused a mint already: its challenge is expected, not retried.
        while (session != null && replays < AiSessionRules.MAX_RETRIES && response.isChallenge()) {
            val fresh = runBlocking { cache.refresh(deviceId, session) } ?: break
            response.close()
            session = fresh
            replays += 1
            response = chain.proceed(request.withSession(session, deviceId))
        }
        return response
    }

    private fun Request.withSession(session: String?, deviceId: String): Request =
        newBuilder()
            .removeHeader(TURNSTILE_HEADER)
            .header(DEVICE_HEADER, deviceId)
            .apply { if (session != null) header(SESSION_HEADER, session) else removeHeader(SESSION_HEADER) }
            .build()

    private fun Response.isChallenge(): Boolean =
        (code == 401 || code == 403) && AiSessionRules.isChallenge(code, peekBody(PEEK_BYTES).string())

    companion object {
        const val SESSION_HEADER = "X-AI-Session"
        const val DEVICE_HEADER = "X-Device-ID"
        const val TURNSTILE_HEADER = "X-Turnstile-Token"
        private const val PEEK_BYTES = 64 * 1024L
    }
}
