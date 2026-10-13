package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.startsWith
import com.lexken.lexkenai.core.auth.AuthTokens
import com.lexken.lexkenai.core.auth.InMemoryTokenStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthInterceptorTest {

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.close()

    private fun client(store: InMemoryTokenStore) =
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()

    @Test
    fun `adds bearer header when token present`() {
        server.enqueue(MockResponse())
        client(InMemoryTokenStore(AuthTokens("abc", null)))
            .newCall(Request.Builder().url(server.url("/me")).build()).execute().close()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer abc")
    }

    @Test
    fun `no header when signed out`() {
        server.enqueue(MockResponse())
        client(InMemoryTokenStore())
            .newCall(Request.Builder().url(server.url("/me")).build()).execute().close()

        assertThat(server.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `opt-out header suppresses auth and is stripped`() {
        server.enqueue(MockResponse())
        client(InMemoryTokenStore(AuthTokens("abc", null)))
            .newCall(
                Request.Builder().url(server.url("/login"))
                    .header(AuthInterceptor.NO_AUTH_HEADER, "1").build(),
            ).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.headers["Authorization"]).isNull()
        assertThat(recorded.headers[AuthInterceptor.NO_AUTH_HEADER]).isNull()
    }

    /** Cloudflare answers `error code: 1010` to generic agents; OkHttp's own `okhttp/<version>` is the one that passes. */
    @Test
    fun `every request carries the okhttp user agent`() {
        server.enqueue(MockResponse())
        client(InMemoryTokenStore())
            .newCall(Request.Builder().url(server.url("/me")).build()).execute().close()

        assertThat(server.takeRequest().headers["User-Agent"]!!).startsWith("okhttp/")
    }
}
