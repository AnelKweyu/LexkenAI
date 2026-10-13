package com.lexken.lexkenai.core.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.lexken.lexkenai.core.auth.session.PasswordChangeFailure
import com.lexken.lexkenai.core.model.Credits
import com.lexken.lexkenai.core.model.User
import com.lexken.lexkenai.core.model.UserId
import com.lexken.lexkenai.core.model.UserRole
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.result.isSuccess
import com.lexken.lexkenai.core.testing.FakeSessionRepository
import com.lexken.lexkenai.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * The two-host bridge against two MockWebServers: sign-up, the codes, the reset and the
 * password change on the identity host; sign-in as VF password grant → identity exchange →
 * Lawris JWT.
 */
class AuthSessionTest {

    private val vfServer = MockWebServer()
    private val lawrisServer = MockWebServer()
    private val tokenStore = InMemoryTokenStore()
    private val session = FakeSessionRepository()

    private lateinit var vfApi: VfOAuthApi
    private lateinit var accountApi: VfAccountApi
    private lateinit var exchangeApi: ExchangeApi
    private lateinit var lawrisApi: LawrisAuthApi

    private val config = VfConfig(
        baseUrl = "unused",
        clientId = "client-id",
        clientSecret = "client-secret",
        bypassKey = "bypass-key",
        bundleId = "com.lexken.mobile",
    )

    @BeforeEach
    fun setUp() {
        vfServer.start()
        lawrisServer.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val factory = json.asConverterFactory("application/json".toMediaType())
        val vf = Retrofit.Builder().baseUrl(vfServer.url("/")).addConverterFactory(factory).build()
        vfApi = vf.create()
        accountApi = vf.create()
        val lawris = Retrofit.Builder().baseUrl(lawrisServer.url("/")).addConverterFactory(factory).build()
        exchangeApi = lawris.create()
        lawrisApi = lawris.create()
    }

    @AfterEach
    fun tearDown() {
        vfServer.close()
        lawrisServer.close()
    }

    private fun repository() = AuthSession(
        vfApi = vfApi,
        exchangeApi = exchangeApi,
        accountApi = accountApi,
        lawrisApi = lawrisApi,
        config = config,
        tokenStore = tokenStore,
        sessionRepository = session,
    )

    private fun renewer() = AuthSessionRenewer(vfApi, exchangeApi, config)
    private fun revoker() = AuthSessionRevoker(vfApi, accountApi, config)
    private fun changer() = IdentityPasswordChanger(vfApi, accountApi, config, tokenStore)

    private fun vfTokenBody(access: String = "vf-access", refresh: String = "vf-refresh") =
        """{"access_token":"$access","expires_in":900,"token_type":"Bearer","refresh_token":"$refresh"}"""

    private fun exchangeBody(jwt: String = "lawris-jwt") =
        """{"ai_service_token":"$jwt","ai_user_id":"uid-1","email":"user@lexken.ai"}"""

    private fun fixture(name: String) = Fixtures.load(name).let { MockResponse(code = it.status, body = it.body) }

    private fun mobileHeaders(request: RecordedRequest) {
        assertThat(request.headers["X-Mobile-Auth-Bypass"]).isEqualTo("bypass-key")
        assertThat(request.headers["X-App-Bundle-Id"]).isEqualTo("com.lexken.mobile")
        assertThat(request.headers["X-Mobile-Nonce"]).isNotNull()
    }

    // ── sign-in ──────────────────────────────────────────────────────────────

    @Test
    fun `sign-in mints, exchanges, stores and refreshes the session`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        lawrisServer.enqueue(MockResponse(code = 200, body = exchangeBody()))

        val result = repository().signIn("user@lexken.ai", "correct-horse-battery")

        assertThat(result.isSuccess).isEqualTo(true)
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh"))
        assertThat(session.refreshCalls).isEqualTo(1)

        val mint = vfServer.takeRequest()
        assertThat(mint.url.encodedPath).isEqualTo("/oauth2/mobile-token/")
        mobileHeaders(mint)

        val exchange = lawrisServer.takeRequest()
        assertThat(exchange.url.encodedPath).isEqualTo("/auth/proxy-exchange-token/")
        assertThat(exchange.headers["Authorization"]).isEqualTo("Bearer vf-access")
    }

    /** Every mint carries a fresh nonce: the identity host masks a replayed one as 404. */
    @Test
    fun `two mints never reuse a nonce`() = runTest {
        repeat(2) {
            vfServer.enqueue(MockResponse(code = 401, body = """{"error":"invalid_grant"}"""))
            repository().signIn("user@lexken.ai", "nope")
        }
        val first = vfServer.takeRequest().headers["X-Mobile-Nonce"]
        val second = vfServer.takeRequest().headers["X-Mobile-Nonce"]
        assertThat(first).isNotNull()
        assertThat(first == second).isEqualTo(false)
    }

    @Test
    fun `wrong password maps to InvalidCredentials`() = runTest {
        vfServer.enqueue(MockResponse(code = 401, body = """{"error":"invalid_grant"}"""))

        assertThat(repository().signIn("user@lexken.ai", "nope").errorOrNull()).isEqualTo(AuthError.InvalidCredentials)
        assertThat(tokenStore.current()).isNull()
    }

    @Test
    fun `the mint's own refusals map apart - email code sent, channel unavailable, misconfigured client, throttle, masked 404`() = runTest {
        // 2FA over email with an unverified address: the host already sent the code, so the
        // credentials are remembered and the code screen can finish the sign-in.
        vfServer.enqueue(fixture("oauth2.mobile-token.email-unverified"))
        val repo = repository()
        assertThat(repo.signIn("wanjiru@kamauadvocates.co.ke", "correct-horse-battery").errorOrNull()).isEqualTo(AuthError.EmailUnverified)
        vfServer.takeRequest()
        vfServer.enqueue(fixture("ems.email-verify.success"))
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        assertThat(repo.verifyEmailOtp("wanjiru@kamauadvocates.co.ke", "123456").getOrNull())
            .isEqualTo(EmailVerificationOutcome.PHONE_VERIFICATION_OFFERED)
        vfServer.takeRequest()
        assertThat(vfServer.takeRequest().body!!.utf8().contains("correct-horse-battery")).isEqualTo(true)

        // 2FA over SMS with no phone on the account: the host's sentence, verbatim.
        vfServer.enqueue(fixture("oauth2.mobile-token.no-phone"))
        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull())
            .isEqualTo(AuthError.SignInBlocked("Unable to send SMS verification code. Please update your phone number and try again."))

        // An OAuth code other than invalid_grant is a build problem — its description is shown, not "wrong password".
        vfServer.enqueue(MockResponse(code = 401, body = """{"error":"invalid_client","error_description":"Invalid client_id parameter value."}"""))
        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull())
            .isEqualTo(AuthError.SignInBlocked("Invalid client_id parameter value."))

        // The 5-per-minute route throttle and the masked 404 (wrong bypass key or replayed nonce) stay data errors.
        vfServer.enqueue(fixture("oauth2.mobile-token.throttled"))
        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull()).isEqualTo(AuthError.Data(DataError.Network.TOO_MANY_REQUESTS))
        vfServer.enqueue(MockResponse(code = 404, body = """{"detail":"Not found."}"""))
        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull()).isEqualTo(AuthError.Data(DataError.Network.NOT_FOUND))
        assertThat(tokenStore.current()).isNull()
        assertThat(lawrisServer.requestCount).isEqualTo(0)
    }

    @Test
    fun `a dead-token exchange asks the identity host whether two-factor is gating it`() = runTest {
        // The mobile grant never ran the 2FA step, so the token is gated: the exchange's 401 is
        // really the host's 403 — one probe of users/me tells them apart.
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        lawrisServer.enqueue(fixture("auth.proxy-exchange-token.dead-token"))
        vfServer.enqueue(fixture("ems.two-factor.gated"))
        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull()).isEqualTo(AuthError.TwoFactorBlocked)
        vfServer.takeRequest()
        val probe = vfServer.takeRequest()
        assertThat(probe.url.encodedPath).isEqualTo("/authentication/users/me/")
        assertThat(probe.headers["Authorization"]).isEqualTo("Bearer vf-access")
        assertThat(tokenStore.current()).isNull()

        // A probe that answers anything else leaves the 401 a data error.
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        lawrisServer.enqueue(fixture("auth.proxy-exchange-token.dead-token"))
        vfServer.enqueue(MockResponse(code = 401, body = """{"detail":"Authentication credentials were not provided."}"""))
        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull()).isEqualTo(AuthError.Data(DataError.Network.UNAUTHORIZED))
    }

    @Test
    fun `an exchange that refuses an unverified email remembers the credentials for the code screen`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        lawrisServer.enqueue(fixture("auth.proxy-exchange-token.unverified"))
        val repo = repository()

        assertThat(repo.signIn("new@lexken.ai", "correct-horse-battery").errorOrNull()).isEqualTo(AuthError.EmailUnverified)
        assertThat(tokenStore.current()).isNull()
        vfServer.takeRequest()
        lawrisServer.takeRequest()

        // Verifying the code now mints with the remembered credentials and holds the session for the phone step.
        vfServer.enqueue(fixture("ems.email-verify.success"))
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        assertThat(repo.verifyEmailOtp("new@lexken.ai", "123456").getOrNull())
            .isEqualTo(EmailVerificationOutcome.PHONE_VERIFICATION_OFFERED)
        vfServer.takeRequest()
        assertThat(vfServer.takeRequest().body!!.utf8().contains("correct-horse-battery")).isEqualTo(true)
    }

    @Test
    fun `any other exchange failure is a data error and rolls nothing in`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        lawrisServer.enqueue(MockResponse(code = 503, body = """{"detail":"Unable to reach the identity service."}"""))

        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull()).isEqualTo(AuthError.Data(DataError.Network.SERVER_ERROR))
        assertThat(tokenStore.current()).isNull()
    }

    @Test
    fun `a failed session refresh rolls the tokens back`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        lawrisServer.enqueue(MockResponse(code = 200, body = exchangeBody()))
        session.refreshError = DataError.Network.SERVER_ERROR

        assertThat(repository().signIn("user@lexken.ai", "pw").errorOrNull()).isEqualTo(AuthError.Data(DataError.Network.SERVER_ERROR))
        assertThat(tokenStore.current()).isNull()
    }

    // ── renewal ──────────────────────────────────────────────────────────────

    @Test
    fun `renewal runs the VF refresh grant then the exchange`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody(access = "vf-access-2", refresh = "vf-refresh-2")))
        lawrisServer.enqueue(MockResponse(code = 200, body = exchangeBody("lawris-jwt-2")))

        val renewed = renewer().renew(AuthTokens("lawris-jwt", "vf-refresh"))

        assertThat(renewed).isEqualTo(AuthTokens("lawris-jwt-2", "vf-refresh-2"))
        assertThat(vfServer.takeRequest().body!!.utf8().contains(""""grant_type":"refresh_token"""")).isEqualTo(true)
        assertThat(lawrisServer.takeRequest().headers["Authorization"]).isEqualTo("Bearer vf-access-2")
    }

    @Test
    fun `renewal is null when the grant is refused, the exchange fails, or there is no refresh token`() = runTest {
        vfServer.enqueue(MockResponse(code = 401, body = """{"error":"invalid_grant"}"""))
        assertThat(renewer().renew(AuthTokens("lawris-jwt", "dead"))).isNull()
        assertThat(lawrisServer.requestCount).isEqualTo(0)

        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody(access = "vf-access-3")))
        lawrisServer.enqueue(MockResponse(code = 503, body = ""))
        assertThat(renewer().renew(AuthTokens("lawris-jwt", "vf-refresh"))).isNull()

        assertThat(renewer().renew(AuthTokens("lawris-jwt", null))).isNull()
    }

    // ── 2FA ──────────────────────────────────────────────────────────────────

    @Test
    fun `a two-factor mint pauses with MfaRequired and stores nothing`() = runTest {
        vfServer.enqueue(
            MockResponse(code = 200, body = """{"access_token":"interim","two_factor_enabled":true,"otp_delivery_method":"whatsapp"}"""),
        )
        assertThat(repository().signIn("user@lexken.ai", "pw").getOrNull()).isEqualTo(SignInOutcome.MfaRequired(OtpDelivery.WHATSAPP))
        assertThat(tokenStore.current()).isNull()
        assertThat(lawrisServer.requestCount).isEqualTo(0)
    }

    @Test
    fun `the 2FA code verifies with the interim bearer, then exchanges`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = """{"access_token":"interim","refresh_token":"vf-refresh","two_factor_enabled":true}"""))
        val repo = repository()
        repo.signIn("user@lexken.ai", "pw")
        vfServer.takeRequest()

        vfServer.enqueue(MockResponse(code = 400, body = """{"detail":"Unable to verify 2FA Code."}"""))
        assertThat(repo.verifyOtp("000000").errorOrNull()).isEqualTo(AuthError.OtpInvalid)
        vfServer.takeRequest()

        // A verified code rotates the session: the interim pair is deleted server-side and the
        // answer carries the verified pair, which is what the exchange and the store get.
        vfServer.enqueue(fixture("ems.login-verify.rotated"))
        lawrisServer.enqueue(MockResponse(code = 200, body = exchangeBody()))
        assertThat(repo.verifyOtp("123456").isSuccess).isEqualTo(true)
        val verify = vfServer.takeRequest()
        assertThat(verify.url.encodedPath).isEqualTo("/authentication/login/verify/")
        assertThat(verify.headers["Authorization"]).isEqualTo("Bearer interim")
        assertThat(lawrisServer.takeRequest().headers["Authorization"]).isEqualTo("Bearer vf-access-verified")
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh-verified"))
        // Nothing is pending any more.
        assertThat(repo.verifyOtp("123456").errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)
    }

    @Test
    fun `a host that only acknowledges the 2FA code keeps exchanging the interim pair`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = """{"access_token":"interim","refresh_token":"vf-refresh","two_factor_enabled":true}"""))
        val repo = repository()
        repo.signIn("user@lexken.ai", "pw")
        vfServer.takeRequest()

        vfServer.enqueue(MockResponse(code = 200, body = """{"success":"2FA Code Verified."}"""))
        lawrisServer.enqueue(MockResponse(code = 200, body = exchangeBody()))
        assertThat(repo.verifyOtp("123456").isSuccess).isEqualTo(true)
        vfServer.takeRequest()
        assertThat(lawrisServer.takeRequest().headers["Authorization"]).isEqualTo("Bearer interim")
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh"))
    }

    @Test
    fun `verifying with no pending sign-in fails with MfaSessionExpired`() = runTest {
        assertThat(repository().verifyOtp("123456").errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)
        assertThat(vfServer.requestCount).isEqualTo(0)
    }

    // ── password reset (EMS code flow) ───────────────────────────────────────

    @Test
    fun `the reset pair posts to the identity host with the mobile headers and never signs in`() = runTest {
        vfServer.enqueue(MockResponse(code = 201, body = """{"email":"user@lexken.ai"}"""))
        assertThat(repository().requestPasswordReset("user@lexken.ai").isSuccess).isEqualTo(true)
        val request = vfServer.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/authentication/password/reset/")
        mobileHeaders(request)

        vfServer.enqueue(MockResponse(code = 400, body = """{"detail":"Invalid code."}"""))
        assertThat(repository().confirmPasswordReset("user@lexken.ai", "000000", "NewPass1!").errorOrNull())
            .isEqualTo(AuthError.ResetCodeInvalid)
        vfServer.takeRequest()

        vfServer.enqueue(MockResponse(code = 200, body = """{"success":"Password reset."}"""))
        assertThat(repository().confirmPasswordReset("user@lexken.ai", "123456", "NewPass1!").isSuccess).isEqualTo(true)
        val confirm = vfServer.takeRequest()
        assertThat(confirm.url.encodedPath).isEqualTo("/authentication/password/reset/verified/")
        assertThat(confirm.body!!.utf8()).isEqualTo("""{"email":"user@lexken.ai","code":"123456","password":"NewPass1!"}""")
        assertThat(tokenStore.current()).isNull()
        assertThat(lawrisServer.requestCount).isEqualTo(0)
    }

    // ── registration on the identity host ────────────────────────────────────

    @Test
    fun `register posts the individual sign-up shape with the mobile headers and parks until the code`() = runTest {
        vfServer.enqueue(fixture("ems.signup.created"))
        val repo = repository()

        val result = repo.register("Wanjiru", "Kamau", "wanjiru@kamauadvocates.co.ke", "+254712345678", "correct-horse-battery")

        assertThat(result.isSuccess).isEqualTo(true)
        val request = vfServer.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/authentication/signup/")
        mobileHeaders(request)
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.body!!.utf8()).isEqualTo(
            """{"email":"wanjiru@kamauadvocates.co.ke","first_name":"Wanjiru","last_name":"Kamau",""" +
                """"password":"correct-horse-battery","phone_number":"+254712345678","account_type":"individual"}""",
        )
        // 201 carries no tokens — nothing signs in until the email code verifies.
        assertThat(tokenStore.current()).isNull()
        assertThat(session.refreshCalls).isEqualTo(0)
        assertThat(lawrisServer.requestCount).isEqualTo(0)
    }

    @Test
    fun `a taken email maps to EmailAlreadyRegistered, any other validation stays a data error`() = runTest {
        vfServer.enqueue(fixture("ems.signup.email-exists"))
        assertThat(repository().register("W", "K", "w@k.co.ke", "+254712345678", "pw-12345678").errorOrNull())
            .isEqualTo(AuthError.EmailAlreadyRegistered)

        vfServer.enqueue(fixture("ems.signup.invalid"))
        assertThat(repository().register("W", "K", "w@k.co.ke", "+254712345678", "pw").errorOrNull())
            .isNotNull().isInstanceOf(AuthError.Data::class)

        // Without the bypass headers the host wants a captcha — the shape a misconfigured build would see.
        vfServer.enqueue(fixture("ems.signup.captcha"))
        assertThat(repository().register("W", "K", "w@k.co.ke", "+254712345678", "pw-12345678").errorOrNull())
            .isEqualTo(AuthError.Data(DataError.Network.UNKNOWN))
        assertThat(tokenStore.current()).isNull()
    }

    // ── the email code, then the held mint and the phone step ────────────────

    @Test
    fun `verifying the email code mints with the remembered credentials and holds the session for the phone step`() = runTest {
        vfServer.enqueue(fixture("ems.signup.created"))
        val repo = repository()
        repo.register("Wanjiru", "Kamau", "wanjiru@kamauadvocates.co.ke", "+254712345678", "correct-horse-battery")
        vfServer.takeRequest()

        vfServer.enqueue(fixture("ems.email-verify.success"))
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))

        val outcome = repo.verifyEmailOtp("wanjiru@kamauadvocates.co.ke", "123456")

        assertThat(outcome).isEqualTo(Result.Success(EmailVerificationOutcome.PHONE_VERIFICATION_OFFERED))
        val verify = vfServer.takeRequest()
        assertThat(verify.url.encodedPath).isEqualTo("/authentication/email/verify/")
        mobileHeaders(verify)
        assertThat(verify.body!!.utf8()).isEqualTo("""{"email":"wanjiru@kamauadvocates.co.ke","code":"123456"}""")
        val mint = vfServer.takeRequest()
        assertThat(mint.url.encodedPath).isEqualTo("/oauth2/mobile-token/")
        assertThat(mint.body!!.utf8().contains("correct-horse-battery")).isEqualTo(true)
        // Held, not signed in: no exchange, nothing stored.
        assertThat(lawrisServer.requestCount).isEqualTo(0)
        assertThat(tokenStore.current()).isNull()

        // The phone step uses the held VF access token.
        vfServer.enqueue(fixture("ems.phone-send.sent"))
        assertThat(repo.requestPhoneOtp().getOrNull()).isEqualTo(PhoneOtpDelivery(OtpDelivery.WHATSAPP))
        val send = vfServer.takeRequest()
        assertThat(send.url.encodedPath).isEqualTo("/authentication/phone/verify/send/")
        assertThat(send.headers["Authorization"]).isEqualTo("Bearer vf-access")
        mobileHeaders(send)

        vfServer.enqueue(fixture("ems.phone-confirm.invalid"))
        assertThat(repo.verifyPhoneOtp("000000").errorOrNull()).isEqualTo(AuthError.OtpInvalid)
        vfServer.takeRequest()
        vfServer.enqueue(fixture("ems.phone-confirm.success"))
        assertThat(repo.verifyPhoneOtp("123456").isSuccess).isEqualTo(true)
        val confirm = vfServer.takeRequest()
        assertThat(confirm.url.encodedPath).isEqualTo("/authentication/phone/verify/confirm/")
        assertThat(confirm.headers["Authorization"]).isEqualTo("Bearer vf-access")
        assertThat(confirm.body!!.utf8()).isEqualTo("""{"code":"123456"}""")

        // Completing exchanges the held mint exactly once and signs in.
        lawrisServer.enqueue(MockResponse(code = 200, body = exchangeBody()))
        assertThat(repo.completePendingSignIn().isSuccess).isEqualTo(true)
        assertThat(lawrisServer.takeRequest().headers["Authorization"]).isEqualTo("Bearer vf-access")
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh"))
        assertThat(session.refreshCalls).isEqualTo(1)
        // Nothing is held any more.
        assertThat(repo.completePendingSignIn().errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)
    }

    @Test
    fun `skipping the phone step completes the sign-in straight from the held mint`() = runTest {
        vfServer.enqueue(fixture("ems.signup.created"))
        val repo = repository()
        repo.register("W", "K", "w@k.co.ke", "+254712345678", "pw-12345678")
        vfServer.takeRequest()
        vfServer.enqueue(fixture("ems.email-verify.success"))
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        repo.verifyEmailOtp("w@k.co.ke", "123456")

        lawrisServer.enqueue(MockResponse(code = 200, body = exchangeBody()))
        assertThat(repo.completePendingSignIn().isSuccess).isEqualTo(true)
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh"))
    }

    @Test
    fun `verifying with no pending registration reports sign-in required and mints nothing`() = runTest {
        vfServer.enqueue(fixture("ems.email-verify.success"))

        val outcome = repository().verifyEmailOtp("someone@lexken.ai", "123456")

        assertThat(outcome).isEqualTo(Result.Success(EmailVerificationOutcome.SIGN_IN_REQUIRED))
        assertThat(vfServer.requestCount).isEqualTo(1)
        assertThat(tokenStore.current()).isNull()
    }

    @Test
    fun `a failed mint after verification still reports the verification as done`() = runTest {
        vfServer.enqueue(fixture("ems.signup.created"))
        val repo = repository()
        repo.register("W", "K", "w@k.co.ke", "+254712345678", "pw-12345678")
        vfServer.takeRequest()

        vfServer.enqueue(fixture("ems.email-verify.success"))
        vfServer.enqueue(MockResponse(code = 503))

        assertThat(repo.verifyEmailOtp("w@k.co.ke", "123456"))
            .isEqualTo(Result.Success(EmailVerificationOutcome.SIGN_IN_REQUIRED))
        assertThat(repo.requestPhoneOtp().errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)
    }

    @Test
    fun `wrong and expired email codes map to their own errors`() = runTest {
        vfServer.enqueue(fixture("ems.email-verify.invalid"))
        assertThat(repository().verifyEmailOtp("a@b.co.ke", "111111").errorOrNull()).isEqualTo(AuthError.OtpInvalid)

        vfServer.enqueue(fixture("ems.email-verify.expired"))
        assertThat(repository().verifyEmailOtp("a@b.co.ke", "111111").errorOrNull()).isEqualTo(AuthError.OtpExpired)
    }

    @Test
    fun `resend posts the email and maps the throttle`() = runTest {
        vfServer.enqueue(fixture("ems.email-resend.success"))
        assertThat(repository().resendEmailOtp("a@b.co.ke").isSuccess).isEqualTo(true)
        val request = vfServer.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/authentication/email/resend_code/")
        mobileHeaders(request)
        assertThat(request.body!!.utf8()).isEqualTo("""{"email":"a@b.co.ke"}""")

        vfServer.enqueue(fixture("ems.email-resend.throttled"))
        assertThat(repository().resendEmailOtp("a@b.co.ke").errorOrNull())
            .isEqualTo(AuthError.Data(DataError.Network.TOO_MANY_REQUESTS))
    }

    @Test
    fun `the phone step with nothing held says the sign-in expired, and a dead held token says so too`() = runTest {
        assertThat(repository().requestPhoneOtp().errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)
        assertThat(repository().verifyPhoneOtp("123456").errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)

        vfServer.enqueue(fixture("ems.signup.created"))
        val repo = repository()
        repo.register("W", "K", "w@k.co.ke", "+254712345678", "pw-12345678")
        vfServer.enqueue(fixture("ems.email-verify.success"))
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody()))
        repo.verifyEmailOtp("w@k.co.ke", "123456")
        repeat(3) { vfServer.takeRequest() }

        vfServer.enqueue(MockResponse(code = 401, body = """{"detail":"Authentication credentials were not provided."}"""))
        assertThat(repo.requestPhoneOtp().errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)
        assertThat(repo.completePendingSignIn().errorOrNull()).isEqualTo(AuthError.MfaSessionExpired)
    }

    // ── sign-out revocation ──────────────────────────────────────────────────

    @Test
    fun `revoking a session turns the refresh into a VF access and logs out on the identity host`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody(access = "vf-access-9", refresh = "vf-refresh-9")))
        vfServer.enqueue(fixture("ems.logout.success"))

        revoker().revoke(AuthTokens("lawris-jwt", "vf-refresh"))

        val grant = vfServer.takeRequest()
        assertThat(grant.url.encodedPath).isEqualTo("/oauth2/mobile-token/")
        assertThat(grant.body!!.utf8().contains(""""grant_type":"refresh_token"""")).isEqualTo(true)
        val logout = vfServer.takeRequest()
        assertThat(logout.method).isEqualTo("GET")
        assertThat(logout.url.encodedPath).isEqualTo("/authentication/logout/")
        assertThat(logout.headers["Authorization"]).isEqualTo("Bearer vf-access-9")
        mobileHeaders(logout)
        assertThat(lawrisServer.requestCount).isEqualTo(0)
    }

    @Test
    fun `a refused refresh grant and a missing refresh token are swallowed`() = runTest {
        vfServer.enqueue(MockResponse(code = 401, body = """{"error":"invalid_grant"}"""))
        revoker().revoke(AuthTokens("lawris-jwt", "vf-refresh")) // no throw, no logout call
        assertThat(vfServer.requestCount).isEqualTo(1)
        revoker().revoke(AuthTokens("lawris-jwt", null))
        assertThat(vfServer.requestCount).isEqualTo(1)
    }

    // ── the password change on the identity host ─────────────────────────────

    private fun signedIn() {
        session.signIn(
            User(id = UserId("user@lexken.ai"), fullName = "User", email = "user@lexken.ai", role = UserRole.ADVOCATE, firmName = null),
            Credits(10),
        )
    }

    @Test
    fun `a password change mints from the refresh, keeps the rotated refresh token, and posts current and new`() = runTest {
        tokenStore.save(AuthTokens("lawris-jwt", "vf-refresh"))
        signedIn()
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody(access = "vf-access-r", refresh = "vf-refresh-r")))
        vfServer.enqueue(fixture("ems.password-change.success"))

        assertThat(changer().change("old-pass", "new-pass-123").isSuccess).isEqualTo(true)

        val grant = vfServer.takeRequest()
        assertThat(grant.url.encodedPath).isEqualTo("/oauth2/mobile-token/")
        assertThat(grant.body!!.utf8().contains(""""grant_type":"refresh_token"""")).isEqualTo(true)
        val change = vfServer.takeRequest()
        assertThat(change.url.encodedPath).isEqualTo("/authentication/password/change/")
        assertThat(change.headers["Authorization"]).isEqualTo("Bearer vf-access-r")
        mobileHeaders(change)
        assertThat(change.body!!.utf8()).isEqualTo("""{"current_password":"old-pass","password":"new-pass-123"}""")
        // The host keeps the session that made the change, so nothing is re-minted: the Lawris
        // JWT stays and the refresh token is the rotated one — two mobile-token calls would have
        // spent the route's 5-per-minute budget for nothing.
        assertThat(vfServer.requestCount).isEqualTo(2)
        assertThat(lawrisServer.requestCount).isEqualTo(0)
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh-r"))
    }

    @Test
    fun `a wrong current password and a rejected new one map apart, and the rotated refresh token survives both`() = runTest {
        tokenStore.save(AuthTokens("lawris-jwt", "vf-refresh"))
        signedIn()
        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody(refresh = "vf-refresh-2")))
        vfServer.enqueue(fixture("ems.password-change.wrong-current"))
        assertThat(changer().change("wrong", "new-pass-123").errorOrNull()).isEqualTo(PasswordChangeFailure.WrongCurrent)
        repeat(2) { vfServer.takeRequest() }
        // The refresh grant consumed "vf-refresh": replaying it later would trip the host's reuse
        // protection and revoke the family, so the rotated token is already stored.
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh-2"))

        vfServer.enqueue(MockResponse(code = 200, body = vfTokenBody(refresh = "vf-refresh-3")))
        vfServer.enqueue(fixture("ems.password-change.rejected"))
        assertThat(changer().change("old-pass", "12345678").errorOrNull())
            .isEqualTo(PasswordChangeFailure.Rejected("This password is too common."))
        repeat(2) { vfServer.takeRequest() }
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh-3"))

        // A refused refresh grant changes nothing and says the session is gone.
        vfServer.enqueue(MockResponse(code = 401, body = """{"error":"invalid_grant"}"""))
        assertThat(changer().change("old-pass", "new-pass-123").errorOrNull())
            .isEqualTo(PasswordChangeFailure.Transport(DataError.Network.UNAUTHORIZED))
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh-3"))
    }

    @Test
    fun `a session without a refresh token cannot change its password here`() = runTest {
        tokenStore.save(AuthTokens("lawris-jwt", null))
        signedIn()
        assertThat(changer().change("a", "b").errorOrNull()).isEqualTo(PasswordChangeFailure.Transport(DataError.Network.UNAUTHORIZED))
        assertThat(vfServer.requestCount).isEqualTo(0)
    }

    // ── the Lawris-host leftovers ────────────────────────────────────────────

    @Test
    fun `distress help posts the android context and accepts the 202`() = runTest {
        lawrisServer.enqueue(MockResponse(code = 202, body = Fixtures.body("auth.distress-help.accepted")))

        val result = repository().requestDistressHelp("a@b.co.ke", "Locked out after web change", "InvalidCredentials")

        assertThat(result.isSuccess).isEqualTo(true)
        val request = lawrisServer.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/auth/distress-help/")
        assertThat(request.body!!.utf8()).isEqualTo(
            """{"email":"a@b.co.ke","message":"Locked out after web change",""" +
                """"context":"android_sign_in","error_details":"InvalidCredentials"}""",
        )
    }

    @Test
    fun `verify-token accepts a live token and maps a dead one to ResetLinkInvalid`() = runTest {
        lawrisServer.enqueue(MockResponse(code = 200, body = Fixtures.body("auth.password-reset.verify-token.valid")))
        assertThat(repository().verifyResetToken("k7hJ2mZq8LxWv3RtNpYd4Ff").isSuccess).isEqualTo(true)
        assertThat(lawrisServer.takeRequest().body!!.utf8()).isEqualTo("""{"token":"k7hJ2mZq8LxWv3RtNpYd4Ff"}""")

        lawrisServer.enqueue(MockResponse(code = 400, body = Fixtures.body("auth.password-reset.verify-token.invalid")))
        assertThat(repository().verifyResetToken("stale").errorOrNull()).isEqualTo(AuthError.ResetLinkInvalid)
    }

    @Test
    fun `confirm posts the token pair and maps token and password rejections apart`() = runTest {
        lawrisServer.enqueue(MockResponse(code = 200, body = Fixtures.body("auth.password-reset.confirm.success")))
        assertThat(repository().confirmResetWithToken("k7hJ2mZq8LxWv3RtNpYd4Ff", "NewPass1!").isSuccess)
            .isEqualTo(true)
        assertThat(lawrisServer.takeRequest().body!!.utf8()).isEqualTo(
            """{"token":"k7hJ2mZq8LxWv3RtNpYd4Ff","new_password":"NewPass1!","confirm_password":"NewPass1!"}""",
        )

        lawrisServer.enqueue(MockResponse(code = 400, body = Fixtures.body("auth.password-reset.verify-token.invalid")))
        assertThat(repository().confirmResetWithToken("stale", "NewPass1!").errorOrNull())
            .isEqualTo(AuthError.ResetLinkInvalid)

        lawrisServer.enqueue(
            MockResponse(code = 400, body = """{"new_password":["New password must be different from your current password."]}"""),
        )
        assertThat(repository().confirmResetWithToken("k7hJ2mZq8LxWv3RtNpYd4Ff", "SamePass1!").errorOrNull())
            .isEqualTo(AuthError.NewPasswordRejected)
    }

    @Test
    fun `the recorded mint and exchange fixtures sign in`() = runTest {
        vfServer.enqueue(MockResponse(code = 200, body = Fixtures.body("oauth2.mobile-token")))
        lawrisServer.enqueue(MockResponse(code = 200, body = Fixtures.body("auth.proxy-exchange-token")))

        val result = repository().signIn("tier1@lexken.test", "correct-horse-battery")

        assertThat(result.isSuccess).isEqualTo(true)
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt-1", "vf-refresh-1"))
        assertThat(lawrisServer.takeRequest().headers["Authorization"]).isEqualTo("Bearer vf-access-1")
    }
}
