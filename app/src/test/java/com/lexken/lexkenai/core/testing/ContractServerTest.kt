package com.lexken.lexkenai.core.testing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.lexken.lexkenai.core.network.OkHttpSocketClient
import com.lexken.lexkenai.core.network.SocketEvent
import com.lexken.lexkenai.core.network.SocketSubscription
import com.lexken.lexkenai.core.network.SocketUrls
import com.lexken.lexkenai.core.testing.ContractServer.Companion.multipartParts
import com.lexken.lexkenai.core.testing.ContractServer.Companion.text
import com.lexken.lexkenai.core.testing.ContractServer.Host
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The rig itself: routing by method + path across two hosts, the api-prefix strip, multipart inspection and socket replay. */
class ContractServerTest {
    private val server = ContractServer()
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun get(base: String, path: String) = client.newCall(Request.Builder().url(base + path).build()).execute()

    @Test
    fun `fixtures route by host, method and path with the api prefix stripped like nginx`() {
        server.serve("users.profile.nav-info", "oauth2.mobile-token")

        val lawris = get(server.lawrisBaseUrl, "users/profile/nav-info/")
        val identity = client.newCall(
            Request.Builder().url(server.identityBaseUrl + "oauth2/mobile-token/").post("{}".toRequestBody()).build(),
        ).execute()

        assertThat(lawris.code).isEqualTo(200)
        assertThat(lawris.body.string()).isEqualTo(Fixtures.body("users.profile.nav-info"))
        assertThat(identity.code).isEqualTo(200)
        assertThat(server.requests(Host.LAWRIS, "GET", "/users/profile/nav-info/")).hasSize(1)
        assertThat(server.requests(Host.IDENTITY, "POST", "/oauth2/mobile-token/")).hasSize(1)
        assertThat(server.unrouted).isEmpty()
    }

    @Test
    fun `the doubled api prefix family keeps one api segment after the strip`() {
        server.serve(Host.LAWRIS, "GET", "/api/playbooks/", body = """{"results":[]}""")

        val response = get(server.lawrisBaseUrl, "api/playbooks/")

        assertThat(response.code).isEqualTo(200)
        assertThat(server.lastRequest(Host.LAWRIS, "GET", "/api/playbooks/").url.encodedPath).isEqualTo("/api/api/playbooks/")
    }

    @Test
    fun `a route served several times answers in order and repeats the last, and a miss is a 404 that is remembered`() {
        server.serve(Host.LAWRIS, "GET", "/sub/current/", 503, """{"detail":"warming up"}""")
        server.serve(Host.LAWRIS, "GET", "/sub/current/", 200, """{"subscription":{"is_expired":false}}""")

        assertThat(get(server.lawrisBaseUrl, "sub/current/").code).isEqualTo(503)
        assertThat(get(server.lawrisBaseUrl, "sub/current/").code).isEqualTo(200)
        assertThat(get(server.lawrisBaseUrl, "sub/current/").code).isEqualTo(200)
        val miss = get(server.lawrisBaseUrl, "sub/plans/")

        assertThat(miss.code).isEqualTo(404)
        assertThat(server.unrouted).containsExactly(ContractServer.Route(Host.LAWRIS, "GET", "/sub/plans/"))
    }

    @Test
    fun `multipart parts are readable by name with filename and content type`() {
        server.serve(Host.LAWRIS, "POST", "/ai/contractual-analysis/", 202, """{"analysis_id":"an-1"}""")
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document".toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("analysis_mode", "pro_client")
            .addFormDataPart("document", "lease-westlands-office.docx", "lease body".toRequestBody(docx))
            .build()
        client.newCall(Request.Builder().url(server.lawrisBaseUrl + "ai/contractual-analysis/").post(body).build()).execute()

        val parts = server.lastRequest(Host.LAWRIS, "POST", "/ai/contractual-analysis/").multipartParts()

        assertThat(parts.map { it.name }).containsExactly("analysis_mode", "document")
        assertThat(parts.text("analysis_mode")).isEqualTo("pro_client")
        assertThat(parts[1].filename).isEqualTo("lease-westlands-office.docx")
        assertThat(parts[1].contentType!!.substringBefore(';')).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertThat(parts[1].text).isEqualTo("lease body")
        assertThat(parts.text("missing")).isNull()
    }

    @Test
    fun `a socket fixture replays after the expected join frame and the close ends the flow`() = runTest {
        server.socket("/ws/ai/lawris-ai/", Fixtures.socket("chat.stream"))
        val socket = OkHttpSocketClient(OkHttpClient())

        val events = socket.connect(
            SocketUrls.chat(server.lawrisBaseUrl),
            openFrames = listOf(SocketSubscription.chatJoinFrame("a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10")),
        ).toList()

        assertThat(events.first()).isEqualTo(SocketEvent.Connected(0))
        val frames = events.filterIsInstance<SocketEvent.Frame>().map { it.text }
        assertThat(frames).hasSize(5)
        assertThat(frames.first()).isEqualTo("""{"type":"stream.start","thread_id":"a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10"}""")
        assertThat(frames.last()).isEqualTo("""{"type":"stream.end","thread_id":"a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10","credits_used":2}""")
        assertThat(events.last()).isInstanceOf(SocketEvent.Closed::class)
        assertThat((events.last() as SocketEvent.Closed).code).isEqualTo(1000)
        assertThat(server.socketFrames["/ws/ai/lawris-ai/"]!!).containsExactly("""{"thread_id":"a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10"}""")
        assertThat(server.socketMismatches).isEmpty()
    }

    @Test
    fun `a client frame that differs from the script is recorded as a mismatch`() = runTest {
        server.socket("/ws/ai/lawris-ai/", Fixtures.socket("chat.stream"))
        val socket = OkHttpSocketClient(OkHttpClient())

        socket.connect(SocketUrls.chat(server.lawrisBaseUrl), openFrames = listOf(SocketSubscription.chatJoinFrame("wrong-thread"))).toList()

        assertThat(server.socketMismatches).hasSize(1)
        assertThat(server.socketMismatches.single().third).isEqualTo("""{"thread_id":"wrong-thread"}""")
    }
}
