package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.auth.AuthTokens
import com.lexken.lexkenai.core.auth.InMemoryTokenStore
import com.lexken.lexkenai.core.auth.SessionRenewer
import com.lexken.lexkenai.core.auth.session.SessionExpiryNotice
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The single-refresh contract: one renewal per 401, one replay, never a loop. */
class TokenAuthenticatorTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.close()

    private class CountingRenewer(private val result: AuthTokens?) : SessionRenewer {
        var calls = 0
        override suspend fun renew(current: AuthTokens): AuthTokens? {
            calls++
            return result
        }
    }

    private fun client(store: InMemoryTokenStore, renewer: SessionRenewer) = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(store))
        .authenticator(TokenAuthenticator(store, renewer))
        .build()

    @Test
    fun `renews once on 401 and replays with the new token`() {
        val store = InMemoryTokenStore(AuthTokens("stale", "vf-refresh"))
        val renewer = CountingRenewer(AuthTokens("fresh", "vf-refresh-2"))
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse())

        client(store, renewer).newCall(Request.Builder().url(server.url("/me")).build()).execute().close()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer stale")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer fresh")
        assertThat(renewer.calls).isEqualTo(1)
        assertThat(runBlocking { store.current() }).isEqualTo(AuthTokens("fresh", "vf-refresh-2"))
    }

    @Test
    fun `failed renewal clears the store and gives up`() {
        val store = InMemoryTokenStore(AuthTokens("stale", "vf-refresh"))
        server.enqueue(MockResponse(code = 401))

        val response = client(store, CountingRenewer(null))
            .newCall(Request.Builder().url(server.url("/me")).build()).execute()

        assertThat(response.code).isEqualTo(401)
        response.close()
        assertThat(runBlocking { store.current() }).isNull()
    }

    @Test
    fun `a second 401 on the replayed call is not retried again`() {
        val store = InMemoryTokenStore(AuthTokens("stale", "vf-refresh"))
        val renewer = CountingRenewer(AuthTokens("fresh", null))
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 401))

        val response = client(store, renewer)
            .newCall(Request.Builder().url(server.url("/me")).build()).execute()

        assertThat(response.code).isEqualTo(401)
        response.close()
        assertThat(renewer.calls).isEqualTo(1)
    }

    @Test
    fun `a call that raced a completed renewal replays without renewing again`() {
        // The store already holds a newer token than the one this response failed with.
        val store = InMemoryTokenStore(AuthTokens("already-renewed", "vf-refresh"))
        val renewer = CountingRenewer(AuthTokens("should-not-be-used", null))
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse())

        val stale = Request.Builder().url(server.url("/me"))
            .header("Authorization", "Bearer stale").build()
        OkHttpClient.Builder()
            .authenticator(TokenAuthenticator(store, renewer))
            .build().newCall(stale).execute().close()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer stale")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer already-renewed")
        assertThat(renewer.calls).isEqualTo(0)
    }

    @Test
    fun `unauthenticated requests are never renewed`() {
        val store = InMemoryTokenStore()
        val renewer = CountingRenewer(AuthTokens("fresh", null))
        server.enqueue(MockResponse(code = 401))

        client(store, renewer).newCall(Request.Builder().url(server.url("/login")).build()).execute().close()

        assertThat(renewer.calls).isEqualTo(0)
    }

    @Test
    fun `a failed renewal arms the session-expiry notice`() {
        SessionExpiryNotice.reset()
        val store = InMemoryTokenStore(AuthTokens("stale", "vf-refresh"))
        server.enqueue(MockResponse(code = 401))

        client(store, CountingRenewer(null)).newCall(Request.Builder().url(server.url("/me")).build()).execute().close()

        assertThat(SessionExpiryNotice.consume()).isTrue()
        assertThat(SessionExpiryNotice.consume()).isFalse()
    }
}
