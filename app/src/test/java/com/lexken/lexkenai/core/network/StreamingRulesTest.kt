package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/** The reconnect backoff, the socket urls and the frame-over-poll rules. */
class StreamingRulesTest {
    private enum class Stage { UPLOAD, VERIFY, PARSE }

    @Test
    fun `backoff doubles from one second, caps, and gives up after the fifth attempt`() {
        val policy = ReconnectPolicy()
        assertThat(policy.delayFor(1)).isEqualTo(1_000)
        assertThat(policy.delayFor(2)).isEqualTo(2_000)
        assertThat(policy.delayFor(3)).isEqualTo(4_000)
        assertThat(policy.delayFor(5)).isEqualTo(15_000) // capped
        assertThat(policy.delayFor(40)).isEqualTo(15_000) // no overflow
        assertThat(policy.shouldRetry(5)).isTrue()
        assertThat(policy.shouldRetry(6)).isFalse()
        assertThat(policy.reconnectAfterServerClose).isFalse() // the chat socket closes on stream.end by design
    }

    @Test
    fun `socket urls drop the api segment and swap the scheme`() {
        assertThat(SocketUrls.base("https://stage.lexken.ai/api/")).isEqualTo("wss://stage.lexken.ai/ws/")
        assertThat(SocketUrls.base("https://lexken.ai/api")).isEqualTo("wss://lexken.ai/ws/")
        assertThat(SocketUrls.base("http://10.0.2.2:8000/api/")).isEqualTo("ws://10.0.2.2:8000/ws/")
        assertThat(SocketUrls.base("http://localhost:4242/")).isEqualTo("ws://localhost:4242/ws/")
        assertThat(SocketUrls.chat("https://stage.lexken.ai/api/")).isEqualTo("wss://stage.lexken.ai/ws/ai/lawris-ai/")
        assertThat(SocketUrls.dmsNotifications("https://stage.lexken.ai/api/", "u-7")).isEqualTo("wss://stage.lexken.ai/ws/dms/notifications/u-7/")
        assertThat(SocketUrls.contractAnalysis("https://stage.lexken.ai/api/", "an-1")).isEqualTo("wss://stage.lexken.ai/ws/ai/contractual-analysis/an-1/")
        assertThat(SocketUrls.gitContract("https://stage.lexken.ai/api/", "repo-1")).isEqualTo("wss://stage.lexken.ai/ws/ai/gitcontract/repo-1/")
        assertThat(SocketUrls.firmKnowledge("https://stage.lexken.ai/api/")).isEqualTo("wss://stage.lexken.ai/ws/firm-knowledge/")
    }

    /** `GeneralAIConsumer.receive` reads `thread_id` off every frame; the join frame is the only JSON the chat socket ever sends. */
    @Test
    fun `the chat subscription joins with the thread id and nothing else`() {
        val subscription = SocketSubscription.chat("https://stage.lexken.ai/api/", "t-42")
        assertThat(subscription.url).isEqualTo("wss://stage.lexken.ai/ws/ai/lawris-ai/")
        assertThat(subscription.openFrames).isEqualTo(listOf("""{"thread_id":"t-42"}"""))
        assertThat(subscription.policy.reconnectAfterServerClose).isFalse()
    }

    @Test
    fun `a frame never moves the bar or the stage backwards, on either rail`() {
        assertThat(StreamOverPollRules.percent(null, 35)).isEqualTo(35)
        assertThat(StreamOverPollRules.percent(35, 20)).isEqualTo(35) // a later snapshot behind the frame
        assertThat(StreamOverPollRules.percent(35, 55)).isEqualTo(55)
        assertThat(StreamOverPollRules.percent(35, null)).isEqualTo(35)
        assertThat(StreamOverPollRules.percent(null, 140)).isEqualTo(100)
        assertThat(StreamOverPollRules.percent(null, null)).isNull()

        assertThat(StreamOverPollRules.stage<Stage>(null, Stage.VERIFY)).isEqualTo(Stage.VERIFY)
        assertThat(StreamOverPollRules.stage(Stage.PARSE, Stage.UPLOAD)).isEqualTo(Stage.PARSE)
        assertThat(StreamOverPollRules.stage(Stage.UPLOAD, Stage.PARSE)).isEqualTo(Stage.PARSE)
        assertThat(StreamOverPollRules.stage(Stage.VERIFY, null)).isEqualTo(Stage.VERIFY)
    }

    @Test
    fun `status lines repeat silently and the text stream appends chunks or takes the full answer`() {
        assertThat(StreamOverPollRules.labelChanged("Drafting", "Drafting")).isFalse()
        assertThat(StreamOverPollRules.labelChanged("Drafting", "Reviewing")).isTrue()
        assertThat(StreamOverPollRules.labelChanged("Drafting", " ")).isFalse()

        assertThat(StreamOverPollRules.text("Dear ", chunk = "Sir", full = null)).isEqualTo("Dear Sir")
        assertThat(StreamOverPollRules.text("Dear Sir", chunk = null, full = "Dear Madam")).isEqualTo("Dear Madam")
        assertThat(StreamOverPollRules.text("Dear Sir", chunk = null, full = null)).isEqualTo("Dear Sir")
    }
}
