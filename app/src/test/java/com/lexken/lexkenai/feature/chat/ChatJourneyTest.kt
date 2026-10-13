package com.lexken.lexkenai.feature.chat

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.auth.SignInOutcome
import com.lexken.lexkenai.core.auth.SignInUseCase
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.multipartParts
import com.lexken.lexkenai.core.testing.ContractServer.Companion.text
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.core.testing.Fixtures
import com.lexken.lexkenai.core.testing.KoinTestRule
import com.lexken.lexkenai.feature.billing.BillingQualifiers
import com.lexken.lexkenai.feature.billing.billingModule
import com.lexken.lexkenai.feature.chat.domain.BriefFileFormat
import com.lexken.lexkenai.feature.chat.domain.BriefFileStatus
import com.lexken.lexkenai.feature.chat.domain.ChatMode
import com.lexken.lexkenai.feature.chat.domain.ComposerSettings
import com.lexken.lexkenai.feature.chat.domain.ConversationKind
import com.lexken.lexkenai.feature.chat.domain.ExplainStatuteUseCase
import com.lexken.lexkenai.feature.chat.domain.FollowUpEvent
import com.lexken.lexkenai.feature.chat.domain.FollowUpUseCase
import com.lexken.lexkenai.feature.chat.domain.GenerateBriefFileUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadBriefHistoryUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadConversationsUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadThreadUseCase
import com.lexken.lexkenai.feature.chat.domain.OpenSourceCardUseCase
import com.lexken.lexkenai.feature.chat.domain.SendRequest
import com.lexken.lexkenai.feature.chat.domain.SendTurnUseCase
import com.lexken.lexkenai.feature.chat.domain.SourceCardOutcome
import com.lexken.lexkenai.feature.chat.domain.ThreadLifecycleRules
import com.lexken.lexkenai.feature.chat.domain.ThreadState
import com.lexken.lexkenai.feature.chat.domain.TurnEffect
import com.lexken.lexkenai.feature.chat.domain.TurnEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.dsl.module

/**
 * T8 for F3 — "a Nairobi associate asks about notice periods, briefs an authority and exports
 * the answer": sign-in, the list, a turn on the wire with its socket replay through the real
 * Koin graph (the AI-session mint refused as it is today, the send still accepted by the
 * contract server), the thread re-read, a source card briefed over the dedicated socket, the
 * follow-up, the brief file poll, a statute read.
 */
class ChatJourneyTest {
    private val server = ContractServer()
    private val thread = "a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10"
    private val briefId = "6e2a9c44-1f3b-4d7e-9a8c-2b5d7f1e3c90"

    @JvmField
    @RegisterExtension
    val koin = KoinTestRule(server, modules = listOf(billingModule, chatModule, module { single(BillingQualifiers.PAYMENT_PROVIDER_NAME) { "paystack" } }))

    @Test
    fun `a Nairobi associate asks about notice periods, briefs an authority and exports the answer`() = runTest {
        server.serve(
            "oauth2.mobile-token",
            "auth.proxy-exchange-token",
            "users.profile.nav-info",
            "sub.ai-credits-balance",
            "users.lawfirm-profile",
            "sub.current",
        )
        server.serve("ai.conversations.list.kenyan", "ai.session.turnstile-required", "ai.lawris-ai.accepted")
        server.serve(Host.LAWRIS, "GET", "/ai/conversations/$thread/messages/", body = Fixtures.body("ai.conversations.messages.case-sources"))
        server.socket("/ws/ai/lawris-ai/", Fixtures.socket("chat.full-turn"))
        server.serve("ai.case-brief.lookup.miss", "ai.brief-selected-cases.accepted", "rag.case.history", "rag.case.follow-up.accepted")
        server.serve("ai.generate-brief.accepted", "ai.brief-status.pending", "ai.brief-status.ready", "ai.statute-explain")

        assertThat(koin.get<SignInUseCase>()("njeri@njeriadvocates.co.ke", "correct-horse-battery")).isEqualTo(Result.Success(SignInOutcome.SignedIn))

        // The sidebar.
        val conversations = koin.get<LoadConversationsUseCase>()().getOrNull()!!
        assertThat(conversations.first().kind).isEqualTo(ConversationKind.CONTRACT_ANALYSIS)

        // The turn: the mint is refused (today's gate), the send goes out without a session and is accepted; the socket replays.
        var state = ThreadLifecycleRules.newChat()
        val events = withContext(Dispatchers.Default) {
            koin.get<SendTurnUseCase>()(
                SendRequest(
                    "What notice must I give a monthly-paid clerk?",
                    ThreadLifecycleRules.threadIdForSend(state),
                    ComposerSettings(mode = ChatMode.GENERAL_AI),
                ),
            ).toList()
        }
        val accepted = (events.first() as TurnEvent.Accepted).accepted
        state = ThreadLifecycleRules.adopt(state, accepted)
        assertThat(state.activeThreadId).isEqualTo(thread)
        assertThat(state.backendThreadId).isEqualTo(thread)
        val send = server.lastRequest(Host.LAWRIS, "POST", "/ai/lawris-ai/")
        assertThat(send.headers["X-AI-Session"]).isEqualTo(null)
        assertThat(send.headers["X-Device-ID"]).isEqualTo("device-7f3a")
        assertThat(send.multipartParts().text("prompt")).isEqualTo("What notice must I give a monthly-paid clerk?")
        assertThat(server.socketFrames["/ws/ai/lawris-ai/"]!!.first()).isEqualTo("""{"thread_id":"$thread"}""")
        val finalTurn = events.filterIsInstance<TurnEvent.Updated>().last().turn
        assertThat(finalTurn.ended).isTrue()
        assertThat(finalTurn.message.content.startsWith("Under section 35(1)(c)")).isTrue()
        assertThat(finalTurn.message.cards.caseSources.single().title).isEqualTo("Kenya Airways Limited v Aviation & Allied Workers Union Kenya [2014] eKLR")
        assertThat(finalTurn.message.cards.statuteSources.single().provisions).containsExactly("s. 35 — Notice of termination")
        assertThat(events.filterIsInstance<TurnEvent.Effect>().map { it.effect }).containsExactly(TurnEffect.CloseSocket)
        assertThat(events.last()).isInstanceOf(TurnEvent.ThreadReloaded::class)
        assertThat(server.socketMismatches).hasSize(0)

        // The thread as persisted.
        val persisted = koin.get<LoadThreadUseCase>()(thread).getOrNull()!!
        assertThat(persisted.single().extractedCases.single().title.startsWith("Michael Rabare Ramoya")).isTrue()

        // A source card: the lookup misses (204), so the brief runs on a dedicated socket.
        server.socket("/ws/ai/lawris-ai/", Fixtures.socket("chat.source-brief"))
        val outcome = koin.get<OpenSourceCardUseCase>()(finalTurn.message.cards.caseSources.single(), state.backendThreadId, finalTurn.message.id)
        assertThat(outcome).isInstanceOf(SourceCardOutcome.Briefing::class)
        val briefs = withContext(Dispatchers.Default) { (outcome as SourceCardOutcome.Briefing).frames.toList() }
        assertThat(briefs.single().id).isEqualTo(briefId)
        assertThat(server.requests(Host.LAWRIS, "POST", "/ai/brief-selected-cases/")).hasSize(1)

        // The brief modal: history, a follow-up over the register_thread socket, the PDF.
        val history = koin.get<LoadBriefHistoryUseCase>()(briefId).getOrNull()!!
        assertThat(history.messages).hasSize(2)
        server.socket("/ws/ai/lawris-ai/", Fixtures.socket("chat.follow-up"))
        val followUp = withContext(Dispatchers.Default) { koin.get<FollowUpUseCase>()(briefId, "What notice period did the lease require?").toList() }
        assertThat(followUp.last()).isInstanceOf(FollowUpEvent.Final::class)
        assertThat(
            server.socketFrames["/ws/ai/lawris-ai/"]!!.last(),
        ).isEqualTo("""{"type":"register_thread","thread_id":"d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70"}""")
        val file = koin.get<GenerateBriefFileUseCase>()(briefId, BriefFileFormat.PDF).toList().last()
        assertThat(file).isInstanceOf(BriefFileStatus.Ready::class)
        assertThat((file as BriefFileStatus.Ready).filename).isEqualTo("case_brief_$briefId.pdf")

        // A statute read from the answer's provision card.
        val explained = koin.get<ExplainStatuteUseCase>()(finalTurn.message.cards.statuteSources.single()).getOrNull()
        assertThat(explained).isNotNull()
        assertThat(server.unrouted).hasSize(0)
    }
}
