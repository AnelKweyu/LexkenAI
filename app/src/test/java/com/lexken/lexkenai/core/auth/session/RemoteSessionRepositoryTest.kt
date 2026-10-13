package com.lexken.lexkenai.core.auth.session

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.auth.AuthTokens
import com.lexken.lexkenai.core.auth.InMemoryTokenStore
import com.lexken.lexkenai.core.model.Credits
import com.lexken.lexkenai.core.model.UserRole
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.Fixtures
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
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
 * The session contract every signed-in screen stands on: nav-info + credits define SignedIn,
 * the firm name is decoration, a rejected token is a state. The reads run concurrently, so
 * responses are routed by path rather than enqueued in order.
 */
class RemoteSessionRepositoryTest {
    private val server = MockWebServer()
    private val tokenStore = InMemoryTokenStore(AuthTokens("lawris-jwt", "vf-refresh"))
    private lateinit var repository: RemoteSessionRepository

    private val responses = mutableMapOf<String, MockResponse>()

    private fun api(): SessionApi {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        return Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
    }

    @BeforeEach
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                responses[request.url.encodedPath] ?: MockResponse(code = 404, body = """{"detail":"Not found."}""")
        }
        server.start()
        repository = RemoteSessionRepository(api(), tokenStore)
        responses["/api/users/profile/nav-info/"] = MockResponse(
            code = 200,
            body = """{"full_name":"Wanjiru Kamau","email":"wanjiru@kamauadvocates.co.ke",
                "role_name":"Advocate","avatar_url":"https://stage.lexken.ai/media/avatars/wk.png"}""",
        )
        responses["/api/users/lawfirm-profile/"] = MockResponse(
            code = 200,
            body = """{"lawfirm_name":"Kamau & Otieno Advocates, Upper Hill, Nairobi"}""",
        )
        responses["/api/sub/ai-credits-balance/"] = MockResponse(
            code = 200,
            body = """{"personal_ai_credits_balance":250,"subscription_ai_credits_balance":1000,"subscription_plan":"SOLO"}""",
        )
    }

    @AfterEach
    fun tearDown() = server.close()

    /** The reads run concurrently, so only the set of paths is deterministic. */
    private fun paths() = (1..4).map { server.takeRequest().url.encodedPath }.toSet()

    @Test
    fun `refresh hits the four session endpoints and maps a signed-in user auth#40 auth#43`() = runTest {
        val state = repository.refresh().getOrNull() as SessionState.SignedIn

        assertThat(state.user.fullName).isEqualTo("Wanjiru Kamau")
        assertThat(state.user.email).isEqualTo("wanjiru@kamauadvocates.co.ke")
        assertThat(state.user.id.value).isEqualTo("wanjiru@kamauadvocates.co.ke")
        assertThat(state.user.role).isEqualTo(UserRole.ADVOCATE)
        assertThat(state.user.firmName).isEqualTo("Kamau & Otieno Advocates, Upper Hill, Nairobi")
        // Personal + subscription pools are one balance to the app.
        // creditService.jsx: subscription || personal || shared || 0 — the first non-zero wins, never the sum.
        assertThat(state.credits).isEqualTo(Credits(1_000))
        assertThat(paths()).isEqualTo(
            setOf("/api/users/profile/nav-info/", "/api/users/lawfirm-profile/", "/api/sub/ai-credits-balance/", "/api/sub/current/"),
        )
    }

    @Test
    fun `role names map admin, lawyer and everything else`() = runTest {
        responses["/api/users/profile/nav-info/"] =
            MockResponse(code = 200, body = """{"full_name":"A","email":"a@x.ke","role_name":"ADMIN"}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).user.role).isEqualTo(UserRole.ADMIN)

        responses["/api/users/profile/nav-info/"] =
            MockResponse(code = 200, body = """{"full_name":"A","email":"a@x.ke","role_name":"lawyer"}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).user.role).isEqualTo(UserRole.ADVOCATE)

        responses["/api/users/profile/nav-info/"] =
            MockResponse(code = 200, body = """{"full_name":"A","email":"a@x.ke","role_name":"public"}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).user.role).isEqualTo(UserRole.PUBLIC)

        responses["/api/users/profile/nav-info/"] =
            MockResponse(code = 200, body = """{"full_name":"A","email":"a@x.ke"}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).user.role).isEqualTo(UserRole.STAFF)
    }

    @Test
    fun `a missing or blank firm profile never fails the session`() = runTest {
        responses["/api/users/lawfirm-profile/"] = MockResponse(code = 404, body = """{"detail":"Not found."}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).user.firmName).isNull()

        responses["/api/users/lawfirm-profile/"] = MockResponse(code = 200, body = """{"lawfirm_name":"   "}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).user.firmName).isNull()

        responses["/api/users/lawfirm-profile/"] = MockResponse(code = 500, body = "")
        assertThat(repository.refresh().getOrNull()).isNotNull().isInstanceOf(SessionState.SignedIn::class)
    }

    @Test
    fun `a negative or absent balance floors at zero credits`() = runTest {
        responses["/api/sub/ai-credits-balance/"] =
            MockResponse(code = 200, body = """{"personal_ai_credits_balance":-40,"subscription_ai_credits_balance":0}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).credits).isEqualTo(Credits(0))

        responses["/api/sub/ai-credits-balance/"] = MockResponse(code = 200, body = "{}")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).credits).isEqualTo(Credits(0))
    }

    @Test
    fun `no stored token is SignedOut without touching the network`() = runTest {
        tokenStore.clear()

        assertThat(repository.refresh().getOrNull()).isEqualTo(SessionState.SignedOut)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a rejected token clears the store and signs out as a state, not an error`() = runTest {
        responses["/api/users/profile/nav-info/"] = MockResponse(code = 401, body = """{"detail":"Token expired"}""")

        assertThat(repository.refresh().getOrNull()).isEqualTo(SessionState.SignedOut)
        assertThat(tokenStore.current()).isNull()
    }

    @Test
    fun `a 404 on nav-info is an error and keeps the token`() = runTest {
        responses["/api/users/profile/nav-info/"] = MockResponse(code = 404, body = """{"detail":"Not found."}""")

        assertThat(repository.refresh().errorOrNull()).isEqualTo(DataError.Network.NOT_FOUND)
        assertThat(tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt", "vf-refresh"))
    }

    @Test
    fun `a server failure on the credits call fails the refresh`() = runTest {
        responses["/api/sub/ai-credits-balance/"] = MockResponse(code = 503, body = "")

        assertThat(repository.refresh().errorOrNull()).isEqualTo(DataError.Network.SERVER_ERROR)
    }

    @Test
    fun `malformed nav-info is a serialization error`() = runTest {
        responses["/api/users/profile/nav-info/"] = MockResponse(code = 200, body = """{"email":"only"}""")

        assertThat(repository.refresh().errorOrNull()).isEqualTo(DataError.Network.SERIALIZATION)
    }

    @Test
    fun `observing the state refreshes once and then follows sign-out`() = runTest {
        repository.state.test {
            assertThat(awaitItem()).isInstanceOf(SessionState.SignedIn::class)

            repository.signOut()

            assertThat(awaitItem()).isEqualTo(SessionState.SignedOut)
            assertThat(tokenStore.current()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(server.requestCount).isEqualTo(4) // nav-info, credits, firm, sub/current
    }

    @Test
    fun `sign-out with the no-op revoker never calls the server and always succeeds`() = runTest {
        assertThat(repository.signOut()).isInstanceOf(Result.Success::class)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(tokenStore.current()).isNull()
    }

    /** The local wipe comes first, the revocation follows on its own scope, best-effort. */
    @Test
    fun `sign-out wipes the store before revoking, hands the revoker the old tokens, and survives its failure`() = runTest {
        val revoked = mutableListOf<AuthTokens>()
        var storeAtRevoke: AuthTokens? = AuthTokens("unset", null)
        val revoking = RemoteSessionRepository(
            api = api(),
            tokenStore = tokenStore,
            revoker = { tokens ->
                storeAtRevoke = tokenStore.current()
                revoked += tokens
                error("the server refused the revocation")
            },
            revokeScope = this,
        )

        assertThat(revoking.signOut()).isInstanceOf(Result.Success::class)
        runCurrent()

        assertThat(revoked).isEqualTo(listOf(AuthTokens("lawris-jwt", "vf-refresh")))
        assertThat(storeAtRevoke).isNull() // wiped before the server heard anything
        assertThat(tokenStore.current()).isNull()
        assertThat(revoking.refresh().getOrNull()).isEqualTo(SessionState.SignedOut)
        assertThat(SessionExpiryNotice.consume()).isFalse() // a deliberate sign-out never arms the notice

        // Nothing stored → nothing to revoke.
        revoking.signOut()
        runCurrent()
        assertThat(revoked.size).isEqualTo(1)
    }

    @Test
    fun `the recorded nav-info and credits fixtures map to a signed-in advocate`() = runTest {
        responses["/api/users/profile/nav-info/"] = MockResponse(code = 200, body = Fixtures.body("users.profile.nav-info"))
        responses["/api/sub/ai-credits-balance/"] = MockResponse(code = 200, body = Fixtures.body("sub.ai-credits-balance"))

        val state = repository.refresh().getOrNull() as SessionState.SignedIn

        assertThat(state.user.fullName).isEqualTo("Tier One")
        assertThat(state.user.email).isEqualTo("tier1@lexken.test")
        assertThat(state.user.role).isEqualTo(UserRole.ADVOCATE)
        assertThat(state.credits).isEqualTo(Credits(1_234))
    }

    @Test
    fun `an expired subscription lands on the session, and a missing read never fails it`() = runTest {
        responses["/api/sub/current/"] = MockResponse(code = 200, body = """{"subscription":{"tier":"SOLO","is_active":false,"is_expired":true}}""")
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).subscriptionExpired).isTrue()

        responses.remove("/api/sub/current/") // the dispatcher answers 404 → not expired, still signed in
        assertThat((repository.refresh().getOrNull() as SessionState.SignedIn).subscriptionExpired).isFalse()
    }

    @Test
    fun `a rejected token arms the sign-in notice as it clears the store`() = runTest {
        SessionExpiryNotice.reset()
        responses["/api/users/profile/nav-info/"] = MockResponse(code = 401, body = """{"detail":"Given token not valid for any token type"}""")

        assertThat(repository.refresh().getOrNull()).isEqualTo(SessionState.SignedOut)
        assertThat(tokenStore.current()).isNull()
        assertThat(SessionExpiryNotice.consume()).isTrue()
        assertThat(SessionExpiryNotice.consume()).isFalse() // once
    }
}
