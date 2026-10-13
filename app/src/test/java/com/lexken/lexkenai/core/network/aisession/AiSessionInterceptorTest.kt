package com.lexken.lexkenai.core.network.aisession

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.lexken.lexkenai.core.auth.AuthTokens
import com.lexken.lexkenai.core.auth.InMemoryTokenStore
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.bodyText
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.core.testing.FakeClock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * The AI-session rail end to end against the contract server: the mint on the plain client,
 * the headers on protected paths, the cache and its expiry, the challenge replays, and the
 * degraded path the app lives on while the Turnstile gate refuses mobile principals.
 */
class AiSessionInterceptorTest {
    private val server = ContractServer()
    private val clock = FakeClock()
    private val tokenStore = InMemoryTokenStore(AuthTokens("lawris-jwt-1", "vf-refresh-1"))
    private lateinit var cache: AiSessionCache
    private lateinit var client: OkHttpClient

    private val chatBody = """{"message":"Notice period under the Employment Act"}"""

    @BeforeEach
    fun setUp() {
        server.start()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val api: AiSessionApi = Retrofit.Builder()
            .baseUrl(server.lawrisBaseUrl)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
        cache = AiSessionCache(RetrofitAiSessionMinter(api, tokenStore, clock, pause = {}), clock)
        client = OkHttpClient.Builder()
            .addInterceptor(AiSessionInterceptor(cache, InMemoryDeviceIdStore("device-7f3a")))
            .build()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun post(path: String, vararg headers: Pair<String, String>) = client.newCall(
        Request.Builder()
            .url(server.lawrisBaseUrl + path)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(chatBody.toRequestBody("application/json".toMediaType()))
            .build(),
    ).execute()

    private fun mints() = server.requests(Host.LAWRIS, "POST", "/ai/session/")
    private fun chats() = server.requests(Host.LAWRIS, "POST", "/ai/lawris-ai/")

    @Test
    fun `an unprotected path is sent untouched and mints nothing`() {
        server.serve(Host.LAWRIS, "GET", "/ai/legal-options/", body = """{"options":[]}""")

        val response = client.newCall(Request.Builder().url(server.lawrisBaseUrl + "ai/legal-options/").build()).execute()

        assertThat(response.code).isEqualTo(200)
        assertThat(mints()).hasSize(0)
        assertThat(server.lastRequest(Host.LAWRIS, "GET", "/ai/legal-options/").headers["X-AI-Session"]).isNull()
        assertThat(server.lastRequest(Host.LAWRIS, "GET", "/ai/legal-options/").headers["X-Device-ID"]).isNull()
    }

    @Test
    fun `a protected path mints once with the bearer and device id, then attaches both headers auth#25`() {
        server.serve("ai.session.created")
        server.serve(Host.LAWRIS, "POST", "/ai/lawris-ai/", body = """{"thread_id":"t-1"}""")

        post("ai/lawris-ai/", "X-Turnstile-Token" to "stale-web-token").use { assertThat(it.code).isEqualTo(200) }
        post("ai/lawris-ai/").use { assertThat(it.code).isEqualTo(200) }

        val mint = mints().single()
        assertThat(mint.headers["Authorization"]).isEqualTo("Bearer lawris-jwt-1")
        assertThat(mint.headers["X-Device-ID"]).isEqualTo("device-7f3a")
        assertThat(mint.bodyText()).isEqualTo("""{"device_id":"device-7f3a"}""")
        val sent = chats()
        assertThat(sent).hasSize(2)
        sent.forEach {
            assertThat(it.headers["X-AI-Session"]).isEqualTo("ais-8c2f6e41")
            assertThat(it.headers["X-Device-ID"]).isEqualTo("device-7f3a")
            assertThat(it.headers["X-Turnstile-Token"]).isNull()
        }
    }

    @Test
    fun `the session is re-minted once the clock passes expires_in minus five seconds`() {
        server.serve("ai.session.created", "ai.session.created")
        server.serve(Host.LAWRIS, "POST", "/ai/lawris-ai/", body = """{"thread_id":"t-1"}""")

        post("ai/lawris-ai/").close()
        clock.advance(600_000L - 5_000L - 1)
        post("ai/lawris-ai/").close()
        assertThat(mints()).hasSize(1)
        clock.advance(2)
        post("ai/lawris-ai/").close()

        assertThat(mints()).hasSize(2)
    }

    @Test
    fun `a challenge re-mints and replays, and the replay carries the new session`() {
        server.serve(
            Host.LAWRIS,
            "POST",
            "/ai/session/",
            200,
            """{"ai_session":"ais-first","expires_in":600,"code":"ai_session_created"}""",
        )
        server.serve(
            Host.LAWRIS,
            "POST",
            "/ai/session/",
            200,
            """{"ai_session":"ais-second","expires_in":600,"code":"ai_session_created"}""",
        )
        server.serve("ai.lawris-ai.session-challenge")
        server.serve(Host.LAWRIS, "POST", "/ai/lawris-ai/", body = """{"thread_id":"t-1"}""")

        val response = post("ai/lawris-ai/")

        assertThat(response.code).isEqualTo(200)
        assertThat(mints()).hasSize(2)
        assertThat(chats().map { it.headers["X-AI-Session"] }).containsExactly("ais-first", "ais-second")
    }

    @Test
    fun `three replays at most, then the last challenge is handed back`() {
        repeat(4) { server.serve("ai.session.created") }
        repeat(4) { server.serve("ai.lawris-ai.session-challenge") }

        val response = post("ai/lawris-ai/")

        assertThat(response.code).isEqualTo(403)
        assertThat(response.body.string()).isEqualTo("""{"code":"ai_session_required","message":"Authentication failed."}""")
        assertThat(mints()).hasSize(4)
        assertThat(chats()).hasSize(4)
    }

    @Test
    fun `a refused mint sends the request without a session, never loops, and is not retried within the cooldown`() {
        server.serve("ai.session.turnstile-required")
        server.serve("ai.lawris-ai.session-challenge")

        val first = post("ai/lawris-ai/")
        val second = post("ai/lawris-ai/")

        assertThat(first.code).isEqualTo(403)
        assertThat(second.code).isEqualTo(403)
        assertThat(mints()).hasSize(1)
        assertThat(chats()).hasSize(2)
        chats().forEach {
            assertThat(it.headers["X-AI-Session"]).isNull()
            assertThat(it.headers["X-Device-ID"]).isEqualTo("device-7f3a")
        }
    }

    @Test
    fun `after the cooldown the mint is tried again`() {
        server.serve("ai.session.turnstile-required", "ai.session.created")
        server.serve("ai.lawris-ai.session-challenge")
        server.serve(Host.LAWRIS, "POST", "/ai/lawris-ai/", body = """{"thread_id":"t-1"}""")

        post("ai/lawris-ai/").close()
        clock.advance(AiSessionCache.DEFAULT_REFUSAL_COOLDOWN_MILLIS)
        val response = post("ai/lawris-ai/")

        assertThat(response.code).isEqualTo(200)
        assertThat(mints()).hasSize(2)
        assertThat(server.lastRequest(Host.LAWRIS, "POST", "/ai/lawris-ai/").headers["X-AI-Session"]).isEqualTo("ais-8c2f6e41")
    }

    @Test
    fun `the mint retries a server error and a body without ai_session, three attempts in all`() {
        server.serve(Host.LAWRIS, "POST", "/ai/session/", 503, """{"detail":"upstream"}""")
        server.serve(Host.LAWRIS, "POST", "/ai/session/", 200, """{"code":"ai_session_created"}""")
        server.serve("ai.session.created")
        server.serve(Host.LAWRIS, "POST", "/ai/lawris-ai/", body = """{"thread_id":"t-1"}""")

        val response = post("ai/lawris-ai/")

        assertThat(response.code).isEqualTo(200)
        assertThat(mints()).hasSize(3)
        assertThat(server.lastRequest(Host.LAWRIS, "POST", "/ai/lawris-ai/").headers["X-AI-Session"]).isEqualTo("ais-8c2f6e41")
    }

    @Test
    fun `no stored bearer means no mint attempt at all`() {
        tokenStore.let { kotlinx.coroutines.runBlocking { it.clear() } }
        server.serve("ai.lawris-ai.session-challenge")

        post("ai/lawris-ai/").close()

        assertThat(mints()).hasSize(0)
        assertThat(chats()).hasSize(1)
    }
}
