package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.lexken.lexkenai.core.result.DataError
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

/** §0.4 of the parity plan: the one 400 the web lists field by field becomes [DataError.Validation]. */
class ValidationErrorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun http(code: Int, body: String) =
        HttpException(Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `a 400 Validation Error lists every field with its messages`() {
        val body = """
            {"error":"Validation Error","message":"Check the highlighted fields.",
             "details":{"phone_number":["Enter a valid Kenyan number, e.g. +254712345678."],
                        "kra_pin":"This field is required.",
                        "non_field_errors":["Start date must precede end date."]}}
        """.trimIndent()

        val error = http(400, body).toApiError(json)

        assertThat(error).isInstanceOf(DataError.Validation::class)
        val validation = error as DataError.Validation
        assertThat(validation.message).isEqualTo("Check the highlighted fields.")
        assertThat(validation.fields["phone_number"]!!).containsExactly("Enter a valid Kenyan number, e.g. +254712345678.")
        assertThat(validation.fields["kra_pin"]!!).containsExactly("This field is required.")
        assertThat(validation.messages).containsExactly(
            "Enter a valid Kenyan number, e.g. +254712345678.",
            "This field is required.",
            "Start date must precede end date.",
        )
    }

    @Test
    fun `a details string lands under non_field_errors`() {
        val error = http(400, """{"error":"Validation Error","details":"The matter number is already taken."}""").toApiError(json)

        assertThat((error as DataError.Validation).fields).isEqualTo(
            mapOf(DataError.Validation.NON_FIELD to listOf("The matter number is already taken.")),
        )
    }

    @Test
    fun `a 400 that is not a Validation Error stays the plain status error for the caller to match`() {
        assertThat(http(400, """{"detail":"Unable to verify 2FA Code."}""").toApiError(json)).isEqualTo(DataError.Network.UNKNOWN)
        assertThat(http(400, """{"error":"Bad things","details":{"x":["y"]}}""").toApiError(json)).isEqualTo(DataError.Network.UNKNOWN)
        assertThat(http(400, "<html>nope</html>").toApiError(json)).isEqualTo(DataError.Network.UNKNOWN)
    }

    @Test
    fun `the parser is usable on a raw body too and answers null for anything else`() {
        assertThat(parseValidationError(json, """{"error":"Validation Error"}""")).isEqualTo(DataError.Validation(emptyMap()))
        assertThat(parseValidationError(json, """{"detail":"x"}""")).isNull()
        assertThat(parseValidationError(json, "not json")).isNull()
    }
}
