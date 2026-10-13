package com.lexken.lexkenai.core.auth

import com.lexken.lexkenai.core.network.AuthInterceptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * Static identity-host configuration; values come from BuildConfig (`VF_*`), which the build
 * fills from `local.properties` or CI environment variables.
 */
data class VfConfig(
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val bypassKey: String,
    /** `X-App-Bundle-Id` — the identity host's registered mobile id, not this app's applicationId. */
    val bundleId: String,
)

/**
 * The sign-in rail: mint a Virtual-Lawfirm OAuth token (mobile grant), then exchange it for a
 * long-lived Lawris JWT. Both APIs run on a plain client — no stored bearer, no authenticator —
 * so a renewal can never recurse into itself.
 *
 * `oauth2/mobile-token/` (`authentication/oauth2_urls.py` `MobileBypassTokenView`) gates **every**
 * grant, refresh included, on the three mobile headers: a wrong or missing bypass key and a
 * replayed nonce both answer a masked 404. Without `X-Mobile-Nonce` the host uses the caller's IP
 * as the nonce for five minutes, so a second mint from the same network would 404 — always send
 * one. The route is throttled at 5 requests per minute per caller, so every path here spends
 * that budget deliberately. No bearer of any kind is read on this route: client auth is the
 * `client_id` / `client_secret` pair in the body.
 */
interface VfOAuthApi {
    @POST("oauth2/mobile-token/")
    suspend fun token(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Body body: VfTokenRequest,
    ): VfTokenResponse
}

/**
 * `lexken_auth/views.py` `LexKenTokenExchangeView`: takes the identity token, refuses an
 * identity whose email is not verified (403), creates the Lawris account on first sight and
 * issues the Lawris JWT.
 */
interface ExchangeApi {
    @POST("auth/proxy-exchange-token/")
    suspend fun exchange(@Header("Authorization") vfBearer: String): ExchangeResponse
}

@Serializable
data class VfTokenRequest(
    @SerialName("grant_type") val grantType: String,
    val username: String? = null,
    val password: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

/**
 * `OAUTH2_PROVIDER` on the identity host: access tokens live 900 s, refresh tokens 72 h and
 * **rotate on every refresh grant** with reuse protection (a consumed refresh token replayed after
 * the 120 s grace window revokes the whole family). Whoever runs a refresh grant must persist the
 * returned refresh token before doing anything else.
 */
@Serializable
data class VfTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
    /** True → the token is interim: 2FA must be verified before it is good for the exchange. */
    @SerialName("two_factor_enabled") val twoFactorEnabled: Boolean? = null,
    @SerialName("otp_delivery_method") val otpDeliveryMethod: String? = null,
)

@Serializable
data class ExchangeResponse(
    @SerialName("ai_service_token") val aiServiceToken: String,
    @SerialName("ai_user_id") val aiUserId: String? = null,
    val email: String? = null,
)

/**
 * The identity host's account endpoints, on the plain client. The three mobile headers stand in
 * for the reCAPTCHA token the web sends: sign-up, the code routes and the reset pair answer
 * field validation with them and `{"detail":"reCAPTCHA verification failed."}` without. The
 * signed-in routes take an explicit bearer because the stored access token is the *Lawris* JWT,
 * never a VF one — callers mint a fresh VF access from the stored refresh grant first.
 */
interface VfAccountApi {
    @POST("authentication/signup/")
    suspend fun signup(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Body body: VfSignupRequest,
    ): VfSignupResponse

    @POST("authentication/email/verify/")
    suspend fun verifyEmail(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Body body: EmailVerifyRequest,
    ): EmsAck

    @POST("authentication/email/resend_code/")
    suspend fun resendEmailCode(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Body body: EmailResendRequest,
    ): EmsAck

    @POST("authentication/password/reset/")
    suspend fun requestPasswordReset(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Body body: PasswordResetRequest,
    ): PasswordResetResponse

    @POST("authentication/password/reset/verified/")
    suspend fun confirmPasswordReset(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Body body: PasswordResetConfirmRequest,
    ): PasswordResetConfirmResponse

    /** Bearer is the interim OAuth access token from a `two_factor_enabled` mint. */
    @POST("authentication/login/verify/")
    suspend fun verifyLoginOtp(
        @Header("Authorization") interimBearer: String,
        @Body body: OtpVerifyRequest,
    ): OtpVerifyResponse

    /** The host picks the channel (`channel`: `sms` | `whatsapp`) and sends a 6-digit code to the account's phone. */
    @POST("authentication/phone/verify/send/")
    suspend fun sendPhoneCode(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Header("Authorization") vfBearer: String,
    ): PhoneCodeSentResponse

    @POST("authentication/phone/verify/confirm/")
    suspend fun confirmPhoneCode(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Header("Authorization") vfBearer: String,
        @Body body: PhoneCodeConfirmRequest,
    ): EmsAck

    /** Requires `current_password`; the host invalidates every token afterwards. */
    @POST("authentication/password/change/")
    suspend fun changePassword(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Header("Authorization") vfBearer: String,
        @Body body: VfPasswordChangeRequest,
    ): EmsAck

    /**
     * The identity-host account behind a VF bearer. Used only to tell a 2FA gate apart from a
     * dead token: `drf_authentication.py` answers 403 `"Two Factor Authentication not verified"`
     * for an unverified 2FA session, and the Lawris exchange flattens that to a 401.
     */
    @GET("authentication/users/me/")
    suspend fun me(@Header("Authorization") vfBearer: String): VfMeResponse

    /** Revokes the bearer's session on the identity host. A GET, as the Virtual Firm app calls it. */
    @GET("authentication/logout/")
    suspend fun logout(
        @Header("X-Mobile-Auth-Bypass") bypassKey: String,
        @Header("X-Mobile-Nonce") nonce: String,
        @Header("X-App-Bundle-Id") bundleId: String,
        @Header("Authorization") vfBearer: String,
    ): Response<Unit>
}

/**
 * `authentication/signup/` for an individual client. [accountType] has no default on purpose:
 * the Json does not encode defaults, and the host requires the key.
 */
@Serializable
data class VfSignupRequest(
    val email: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val password: String,
    /** E.164, `+254712345678`. */
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("account_type") val accountType: String,
)

/** 200/201 echoes the fields; nothing in it is needed. */
@Serializable
data class VfSignupResponse(
    val email: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("account_type") val accountType: String? = null,
)

@Serializable
data class EmailVerifyRequest(val email: String, val code: String)

@Serializable
data class EmailResendRequest(val email: String)

@Serializable
data class PhoneCodeConfirmRequest(val code: String)

/** `authentication/users/me/` — only the flags the auth rail reads; tolerant of everything else. */
@Serializable
data class VfMeResponse(
    val email: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null,
    @SerialName("phone_verified") val phoneVerified: Boolean? = null,
    @SerialName("two_factor_enabled") val twoFactorEnabled: Boolean? = null,
    @SerialName("otp_delivery_method") val otpDeliveryMethod: String? = null,
    /** The host asks for a new password on the next sign-in (temporary credentials). */
    @SerialName("change_password") val changePassword: Boolean? = null,
)

/** `{success, channel}` — [channel] is `sms` or `whatsapp`. */
@Serializable
data class PhoneCodeSentResponse(val success: String? = null, val channel: String? = null)

@Serializable
data class VfPasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String,
    val password: String,
)

/** The host's 2xx acknowledgements are small JSON objects (`{"success": …}` / `{"detail": …}`); tolerant. */
@Serializable
data class EmsAck(val success: String? = null, val detail: String? = null, val message: String? = null)

@Serializable
data class PasswordResetRequest(val email: String)

/** 201 `{email}` — tolerant, nothing in it is needed. */
@Serializable
data class PasswordResetResponse(val email: String? = null)

@Serializable
data class PasswordResetConfirmRequest(
    val email: String,
    val code: String,
    val password: String,
)

/** 200 `{"success":"Password reset."}` — tolerant, nothing in it is needed. */
@Serializable
data class PasswordResetConfirmResponse(val success: String? = null)

@Serializable
data class OtpVerifyRequest(val code: String)

/**
 * `LoginVerify` (`authentication/views.py`): a verified code **rotates the session** — the
 * interim pair is deleted and a fresh `access_token` / `refresh_token` comes back in the body
 * (`services/oauth_tokens.py` `rotate_oauth_session_after_login_2fa`). The exchange must use
 * the new access token; the interim one is dead the moment this answers 200.
 * 400 `{"detail":"Unable to verify 2FA Code."}`.
 */
@Serializable
data class OtpVerifyResponse(
    val detail: String? = null,
    val success: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
)

/**
 * What is left of the Lawris-host auth surface after the move to the identity rail: the
 * distress-help sheet and the emailed reset link's token pair. Both are reached through the
 * tenant base URL with no bearer (the [AuthInterceptor.NO_AUTH_HEADER] opt-out). Lawris
 * `auth/login/` and `auth/register/` sit behind Cloudflare Turnstile and are never called.
 */
interface LawrisAuthApi {
    @Headers("${AuthInterceptor.NO_AUTH_HEADER}: 1")
    @POST("auth/distress-help/")
    suspend fun distressHelp(@Body body: DistressHelpRequest): MessageResponse

    @Headers("${AuthInterceptor.NO_AUTH_HEADER}: 1")
    @POST("auth/password-reset/verify-token/")
    suspend fun verifyResetToken(@Body body: ResetTokenRequest): SuccessResponse

    @Headers("${AuthInterceptor.NO_AUTH_HEADER}: 1")
    @POST("auth/password-reset/confirm/")
    suspend fun confirmPasswordReset(@Body body: ResetConfirmRequest): SuccessResponse
}

@Serializable
data class DistressHelpRequest(
    val email: String,
    val message: String,
    val context: String? = null,
    @SerialName("error_details") val errorDetails: String? = null,
)

@Serializable
data class ResetTokenRequest(val token: String)

@Serializable
data class ResetConfirmRequest(
    val token: String,
    @SerialName("new_password") val newPassword: String,
    @SerialName("confirm_password") val confirmPassword: String,
)

/** Generic `{message}` / `{status,message}` 2xx body — tolerant, nothing in it is needed. */
@Serializable
data class MessageResponse(
    val message: String? = null,
    val status: String? = null,
)

/** `{success, message, …}` from the password-reset pair — tolerant. */
@Serializable
data class SuccessResponse(val success: Boolean? = null, val message: String? = null)
