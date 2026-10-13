package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.lexken.lexkenai.core.result.DataError
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun httpError(code: Int, body: String): HttpException =
        HttpException(Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `machine codes on 403 map to typed api errors`() {
        assertThat(httpError(403, """{"error_code":"INSUFFICIENT_AI_CREDITS","detail":{}}""").toApiError(json))
            .isEqualTo(DataError.Api.INSUFFICIENT_CREDITS)
        assertThat(httpError(403, """{"error_code":"NO_VALID_SUBSCRIPTION"}""").toApiError(json))
            .isEqualTo(DataError.Api.NO_SUBSCRIPTION)
        assertThat(httpError(403, """{"error_code":"AI_PLAN_RESTRICTION"}""").toApiError(json))
            .isEqualTo(DataError.Api.PLAN_RESTRICTED)
        assertThat(httpError(403, """{"code":"turnstile_required","message":"Authentication failed."}""").toApiError(json))
            .isEqualTo(DataError.Api.SESSION_CHALLENGE)
    }

    /** `security/permissions.py`: the routes behind the browser-only AI session mint. */
    @Test
    fun `ai_session_required is the session challenge`() {
        assertThat(httpError(403, """{"code":"ai_session_required","detail":"AI session required."}""").toApiError(json))
            .isEqualTo(DataError.Api.SESSION_CHALLENGE)
    }

    @Test
    fun `SUBSCRIPTION_REQUIRED on top-up maps to no-subscription`() {
        assertThat(httpError(403, """{"error_code":"SUBSCRIPTION_REQUIRED","redirect_to":"/pay"}""").toApiError(json))
            .isEqualTo(DataError.Api.NO_SUBSCRIPTION)
    }

    @Test
    fun `unknown codes fall back to the plain status mapping`() {
        assertThat(httpError(403, """{"error_code":"SOMETHING_NEW"}""").toApiError(json))
            .isEqualTo(DataError.Network.FORBIDDEN)
    }

    @Test
    fun `non-json and empty bodies fall back to the plain status mapping`() {
        assertThat(httpError(401, "<html>gateway</html>").toApiError(json))
            .isEqualTo(DataError.Network.UNAUTHORIZED)
        assertThat(httpError(403, "").toApiError(json)).isEqualTo(DataError.Network.FORBIDDEN)
    }

    @Test
    fun `other statuses never consult the body`() {
        assertThat(httpError(500, """{"error_code":"INSUFFICIENT_AI_CREDITS"}""").toApiError(json))
            .isEqualTo(DataError.Network.SERVER_ERROR)
    }

    @Test
    fun `a 402 is the credits wall whatever the body says`() {
        assertThat(httpError(402, """{"detail":"Insufficient credits to run this request.","required_credits":100}""").toApiError(json))
            .isEqualTo(DataError.Api.INSUFFICIENT_CREDITS)
        assertThat(httpError(402, "").toApiError(json)).isEqualTo(DataError.Api.INSUFFICIENT_CREDITS)
    }
}
