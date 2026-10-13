package com.lexken.lexkenai.core.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** The dialects of a non-2xx body across the two hosts, and the sentence each one yields. */
class ErrorBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): ErrorBody? = parseErrorBody(json, text)

    @Test
    fun `an OAuth refusal keeps the code and prefers the description as its sentence`() {
        val body = parse("""{"error":"invalid_client","error_description":"Invalid client_id parameter value."}""")!!
        assertThat(body.error).isEqualTo("invalid_client")
        assertThat(body.errorDescription).isEqualTo("Invalid client_id parameter value.")
        assertThat(body.firstSentence).isEqualTo("Invalid client_id parameter value.")
        // A bare code is still a sentence of last resort.
        assertThat(parse("""{"error":"invalid_grant"}""")!!.firstSentence).isEqualTo("invalid_grant")
    }

    @Test
    fun `a DRF detail with a boolean marker beside it exposes both`() {
        val body = parse(
            """{"detail":"Email verification is required before Email OTP can be used.","email_verification_required":true,"email":"a@b.co.ke"}""",
        )!!
        assertThat(body.detail).isEqualTo("Email verification is required before Email OTP can be used.")
        assertThat(body.flag("email_verification_required")).isTrue()
        assertThat(body.flag("email")).isFalse()
        assertThat(body.mentions("email verification")).isTrue()
    }

    @Test
    fun `serializer rejections read field errors and non_field_errors`() {
        val body = parse("""{"email":["user with this email already exists."],"non_field_errors":["Passwords do not match."]}""")!!
        assertThat(body.field("email")).isEqualTo("user with this email already exists.")
        assertThat(body.firstSentence).isEqualTo("Passwords do not match.")
        assertThat(parse("""{"current_password":["Current password is incorrect."]}""")!!.firstSentence)
            .isEqualTo("Current password is incorrect.")
    }

    @Test
    fun `the identity host's problem payload yields its code and detail`() {
        val body = parse("""{"code":"otp_delivery_failed","detail":"Unable to send SMS verification code.","status":400,"title":"OTP error"}""")!!
        assertThat(body.code).isEqualTo("otp_delivery_failed")
        assertThat(body.firstSentence).isEqualTo("Unable to send SMS verification code.")
    }

    @Test
    fun `Lawris error_code bodies and messages still parse`() {
        val body = parse("""{"error":"AI session required","error_code":"ai_session_required","message":"Open the web app once."}""")!!
        assertThat(body.code).isEqualTo("ai_session_required")
        assertThat(body.firstSentence).isEqualTo("Open the web app once.")
    }

    @Test
    fun `HTML pages, blank strings and non-objects parse to nothing`() {
        assertThat(parse("<!DOCTYPE html><html><body>Server Error (500)</body></html>")).isNull()
        assertThat(parse("  <html>error code: 1010</html>")).isNull()
        assertThat(parse("[1,2]")).isNull()
        assertThat(parse("not json")).isNull()
        assertThat(parse("""{"detail":""}""")!!.detail).isNull()
    }
}
