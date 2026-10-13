package com.lexken.lexkenai.core

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.lexken.lexkenai.core.auth.AuthTokens
import com.lexken.lexkenai.core.auth.SignInOutcome
import com.lexken.lexkenai.core.auth.SignInUseCase
import com.lexken.lexkenai.core.auth.session.ObserveSessionUseCase
import com.lexken.lexkenai.core.auth.session.SessionState
import com.lexken.lexkenai.core.auth.session.SignOutUseCase
import com.lexken.lexkenai.core.model.Credits
import com.lexken.lexkenai.core.network.JobEvent
import com.lexken.lexkenai.core.network.JobTracker
import com.lexken.lexkenai.core.network.PollSchedule
import com.lexken.lexkenai.core.network.SocketSubscription
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.bodyText
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.core.testing.Fixtures
import com.lexken.lexkenai.core.testing.KoinTestRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Rung T8 for the core: the real Koin graph against the contract server. Sign-in runs the
 * identity mint, the exchange and the session reads on the wire exactly as the app does;
 * sign-out revokes on the identity host; a tracked job sees the chat socket's frames beside
 * its poll. Every feature journey from P1 on is built the same way.
 */
class CoreJourneyTest {
    private val server = ContractServer()

    @JvmField
    @RegisterExtension
    val koin = KoinTestRule(server)

    @Test
    fun `sign in mints, exchanges and reads the session, then sign out clears it and revokes on the identity host`() = runTest {
        server.serve("oauth2.mobile-token", "auth.proxy-exchange-token", "users.profile.nav-info", "sub.ai-credits-balance", "ems.logout.success")
        server.serve(Host.LAWRIS, "GET", "/users/lawfirm-profile/", body = """{"lawfirm_name":"Mwangi & Otieno Advocates"}""")
        server.serve(Host.LAWRIS, "GET", "/sub/current/", body = """{"subscription":{"is_expired":false}}""")

        val outcome = koin.get<SignInUseCase>()("wanjiru@mwangiotieno.co.ke", "correct-horse-battery")

        assertThat(outcome).isEqualTo(Result.Success(SignInOutcome.SignedIn))
        val state = koin.get<ObserveSessionUseCase>()().first { it !is SessionState.Unknown }
        assertThat(state).isInstanceOf(SessionState.SignedIn::class)
        val signedIn = state as SessionState.SignedIn
        assertThat(signedIn.user.fullName).isEqualTo("Tier One")
        assertThat(signedIn.user.firmName).isEqualTo("Mwangi & Otieno Advocates")
        assertThat(signedIn.credits).isEqualTo(Credits(1234))
        assertThat(koin.tokenStore.current()).isEqualTo(AuthTokens("lawris-jwt-1", "vf-refresh-1"))

        val mint = server.lastRequest(Host.IDENTITY, "POST", "/oauth2/mobile-token/")
        assertThat(mint.headers["X-Mobile-Auth-Bypass"]).isEqualTo("bypass-key")
        assertThat(mint.headers["X-App-Bundle-Id"]).isEqualTo("com.lexken.mobile")
        assertThat(mint.headers["X-Mobile-Nonce"]).isNotNull()
        assertThat(mint.headers["Authorization"]).isNull()
        assertThat(mint.bodyText()).contains(""""grant_type":"password"""")
        assertThat(server.lastRequest(Host.LAWRIS, "POST", "/auth/proxy-exchange-token/").headers["Authorization"]).isEqualTo("Bearer vf-access-1")
        assertThat(server.lastRequest(Host.LAWRIS, "GET", "/users/profile/nav-info/").headers["Authorization"]).isEqualTo("Bearer lawris-jwt-1")
        assertThat(server.unrouted).hasSize(0)

        koin.get<SignOutUseCase>()()

        assertThat(koin.get<ObserveSessionUseCase>()().first()).isEqualTo(SessionState.SignedOut)
        assertThat(koin.tokenStore.current()).isNull()
        // The revocation runs off the caller's scope: a refresh grant, then the identity host's logout.
        val logout = awaitRequest { server.requests(Host.IDENTITY, "GET", "/authentication/logout/").firstOrNull() }
        assertThat(logout.headers["Authorization"]).isEqualTo("Bearer vf-access-1")
        assertThat(server.requests(Host.IDENTITY, "POST", "/oauth2/mobile-token/")).hasSize(2)
        assertThat(server.requests(Host.IDENTITY, "POST", "/oauth2/mobile-token/")[1].bodyText()).contains(""""grant_type":"refresh_token"""")
    }

    @Test
    fun `a tracked job through the graph streams the chat socket beside its poll and ends on the terminal read`() = runTest {
        server.socket("/ws/ai/lawris-ai/", Fixtures.socket("chat.stream"))
        val tracker = koin.get<JobTracker>()
        val threadId = "a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10"
        var streamEnded = false

        val fetch: suspend () -> Result<String, DataError> = { Result.Success(if (streamEnded) "done" else "running") }

        val events: List<JobEvent<String>> = withContext(Dispatchers.Default) {
            tracker.track(
                schedule = PollSchedule(initialMillis = 50, maxMillis = 50, capMillis = 10_000, backoffFactor = 1.0),
                isTerminal = { status: String -> status == "done" },
                socket = SocketSubscription.chat(server.lawrisBaseUrl, threadId),
                fetch = fetch,
            )
                .onEach { event -> if (event is JobEvent.Live && event.frame.contains("stream.end")) streamEnded = true }
                .toList()
        }

        val live = events.filterIsInstance<JobEvent.Live>().map { it.frame }
        assertThat(live).hasSize(5)
        assertThat(live.last()).contains(""""type":"stream.end"""")
        assertThat(events.last()).isEqualTo(JobEvent.Terminal("done"))
        assertThat(server.socketFrames["/ws/ai/lawris-ai/"]!!).containsExactly("""{"thread_id":"$threadId"}""")
        assertThat(server.socketMismatches).hasSize(0)
    }

    private suspend fun <T : Any> awaitRequest(timeoutMillis: Long = 5_000, read: () -> T?): T =
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) {
                var found = read()
                while (found == null) {
                    delay(20)
                    found = read()
                }
                found
            }
        }
}
