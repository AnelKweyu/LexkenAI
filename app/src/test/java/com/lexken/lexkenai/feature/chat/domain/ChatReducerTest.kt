package com.lexken.lexkenai.feature.chat.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.testing.Fixtures
import com.lexken.lexkenai.core.testing.SocketStep
import com.lexken.lexkenai.feature.billing.domain.CreditsWall
import org.junit.jupiter.api.Test

/** T3 for F3: every inbound event type of the chat socket (appendix §1), the case-search socket (§2) and the follow-up socket (§4). */
class ChatReducerTest {
    private fun start(flow: TurnFlow = TurnFlow.GENERAL) =
        ChatStreamReducer.start(flow, "LexKen AI", OptimisticMessages.acceptedAssistant(1_000L, flow, "LexKen AI"))

    private fun replay(flow: TurnFlow, fixture: String): List<ChatTurn> {
        var turn = start(flow)
        val turns = mutableListOf<ChatTurn>()
        Fixtures.socket(fixture).steps.filterIsInstance<SocketStep.Server>().forEach { step ->
            turn = ChatStreamReducer.reduce(turn, step.frame)
            turns += turn
        }
        return turns
    }

    @Test
    fun `a general turn swaps the id on stream_start, orders progress, streams chunks, collects sources and ends on stream_end`() {
        val turns = replay(TurnFlow.GENERAL, "chat.full-turn")
        val end = turns.last()

        assertThat(end.message.id).isEqualTo("42fc18c8-28b1-431d-8424-6c38cd82935f")
        assertThat(end.message.createdAt).isEqualTo("2026-10-13T06:22:14.503720+03:00")
        // The thinking sentinel is dropped, the stale run and the duplicate sequence are ignored.
        val afterProgress = turns[5]
        assertThat(
            afterProgress.message.progressSteps,
        ).containsExactly("Searching relevant Kenyan statutes and provisions...", "Reading the Employment Act, 2007")
        assertThat(turns[6].message.progressSteps).isEqualTo(afterProgress.message.progressSteps)
        assertThat(end.message.draftingRunId).isEqualTo("run-1")
        // Chunks accumulate after the placeholder is cleared; the final response replaces the text.
        assertThat(
            turns[8].message.content,
        ).isEqualTo("Under section 35(1)(c) of the Employment Act, 2007, an employee paid monthly is entitled to twenty-eight days of notice.")
        assertThat(turns[8].streaming).isTrue()
        assertThat(
            end.message.content,
        ).isEqualTo(
            "Under section 35(1)(c) of the Employment Act, 2007, an employee paid monthly is entitled to twenty-eight days of notice, " +
                "or pay in lieu (section 36).",
        )
        assertThat(end.message.cards.statuteSources.single().provisions).containsExactly("s. 35 — Notice of termination")
        assertThat(end.message.cards.caseSources.single().id).isEqualTo("case-77")
        assertThat(end.message.cards.webSources.single().snippets).containsExactly("Twenty-eight days for monthly-paid employees.")
        assertThat(end.message.cards.searchQueries).containsExactly("employment act 2007 notice period")
        assertThat(turns[11].message.progressSteps.last()).isEqualTo("Found 1 relevant source from web search")
        assertThat(end.ended).isTrue()
        assertThat(end.streaming).isFalse()
        assertThat(end.message.isProcessing).isFalse()
        assertThat(end.message.progressSteps).isEmpty()
        assertThat(end.effects).containsExactly(TurnEffect.CloseSocket)
    }

    @Test
    fun `a rag turn never streams chunks, offers the mentioned cases, keeps running past stream_end until the briefing batch completes, and dedupes briefs`() {
        val turns = replay(TurnFlow.RAG, "chat.rag-briefing")

        val analysed = turns[2]
        assertThat(
            analysed.message.cards.mentionedCases.map {
                it.name
            },
        ).containsExactly("Karanja v Mwaura [2019] eKLR", "Ramoya v Barasa [2020] KEELC 930 (KLR)", "Odinga v IEBC [2017] eKLR")
        assertThat(analysed.message.content).isEqualTo("Three authorities bear on adverse possession here.")
        assertThat(analysed.briefingPending).isTrue()
        assertThat(turns[3].ended).isFalse()
        assertThat(turns[4].message.extractedCases.single().title).isEqualTo("Karanja v Mwaura")
        assertThat(turns[4].message.progressSteps.last()).isEqualTo("Briefed: Karanja v Mwaura")
        assertThat(turns[5].message.extractedCases).hasSize(1)
        assertThat(turns[6].briefingPending).isFalse()
        assertThat(turns.last().ended).isTrue()
        assertThat(
            ChatStreamReducer.reduce(start(TurnFlow.RAG), """{"type":"stream.chunk","message":"ignored"}""").message.content,
        ).isEqualTo("Processing query...")
    }

    @Test
    fun `a submission handoff reports progress on its card and completion reloads the thread`() {
        val turns = replay(TurnFlow.GENERAL, "chat.submission-handoff")

        assertThat(turns[0].message.cards.submissionHandoff!!.tool).isEqualTo(HandoffTool.SUBMISSION_ANALYZER)
        assertThat(turns[0].message.cards.submissionHandoff!!.sessionId).isEqualTo("sess-koech-1")
        assertThat(turns[2].message.cards.submissionHandoff!!.message).isEqualTo("Verifying Kamau v Republic [2018] eKLR")
        assertThat(turns[3].effects).containsExactly(TurnEffect.ReloadThread, TurnEffect.CloseSocket)
        assertThat(turns[3].ended).isTrue()
    }

    @Test
    fun `a dms handoff auto-opens once per task, then the bundle offer, the briefing countdown and the generation card land`() {
        val turns = replay(TurnFlow.GENERAL, "chat.dms-handoff")

        assertThat(turns[0].effects.single()).isInstanceOf(TurnEffect.AutoOpenHandoff::class)
        assertThat(turns[1].effects).isEmpty()
        assertThat(turns[1].message.cards.dmsHandoff!!.progress).isEqualTo(40)
        val offer = turns[2].message.cards.documentBundleOffer!!
        assertThat(offer.documents.map { it.name }).containsExactly("Demand Letter", "Plaint", "List of Witnesses")
        assertThat(offer.defaultDocumentLimit).isEqualTo(2)
        val confirm = turns[3].message.cards.briefingConfirm!!
        assertThat(confirm.cases).hasSize(5)
        assertThat(confirm.timeoutSeconds).isEqualTo(120)
        assertThat(turns[3].effects.single()).isEqualTo(TurnEffect.StartBriefingCountdown(confirm))
        assertThat(turns[4].message.cards.documentBundleOffer).isNull()
        assertThat(
            turns[4].message.cards.documentGeneration,
        ).isEqualTo(DocumentGenerationCard("suits:suit_2", "Breach of contract", listOf("Demand Letter", "Plaint"), "run-77", "gen-task-77", "running"))
    }

    @Test
    fun `a pro-drafter turn shows a processing draft on the stage frame, then the artifact with a signed url`() {
        val turns = replay(TurnFlow.GENERAL, "chat.pro-drafter")

        assertThat(turns[0].message.cards.draftDocument).isEqualTo(DraftDocumentCard("Demand letter - Ramoya", "processing", messageId = "assistant-1000"))
        assertThat(turns[0].message.draftingRunId).isEqualTo("run-4c1d2e3f")
        val artifact = turns[2].message.cards.draftingArtifact!!
        assertThat(artifact.downloadUrl).isEqualTo("/api/drafting/artifacts/art-7d4e/download/")
        assertThat(turns[2].message.cards.draftDocument!!.needsSignedUrl).isTrue()
        assertThat(turns[2].message.cards.draftDocument!!.canReplace).isTrue()
        assertThat(turns[3].message.content).isEqualTo("Your demand letter is ready.")
        assertThat(turns[3].message.cards.draftDocument!!.documentName).isEqualTo("Demand_letter_Ramoya.docx")
    }

    @Test
    fun `a partial_success response without a file marks the draft as an error`() {
        val turn = ChatStreamReducer.reduce(start(), """{"type":"full_llm_response","message":"Text only","metadata":{"status":"partial_success"}}""")
        assertThat(turn.message.cards.draftDocument!!.status).isEqualTo("error")
        assertThat(turn.message.cards.draftDocument!!.errorMessage).isEqualTo(DraftDocumentRules.PARTIAL_SUCCESS_MESSAGE)
    }

    @Test
    fun `rag_error, error and credits frames append the error, close the socket and queue the wall`() {
        val error = replay(TurnFlow.RAG, "chat.rag-error")[1]
        assertThat(error.message.content).isEqualTo("**Error:** The retrieval pipeline failed: upstream timeout.")
        assertThat(error.effects).containsExactly(TurnEffect.Failed("The retrieval pipeline failed: upstream timeout."), TurnEffect.CloseSocket)
        assertThat(error.ended).isTrue()

        val credits = replay(TurnFlow.GENERAL, "chat.credits-insufficient")[1]
        assertThat(credits.effects[0]).isEqualTo(TurnEffect.ShowCreditsWall(CreditsWall("You do not have enough credits for this request.", 0, null)))
        assertThat(credits.message.content).isEqualTo("**Error:** You do not have enough credits for this request.")

        val subscription = ChatStreamReducer.reduce(
            start(),
            """{"type":"subscription.error","message":"No plan","data":{"personal_balance":3,"required_credits":10}}""",
        )
        assertThat(subscription.effects[0]).isEqualTo(TurnEffect.ShowSubscriptionWall(CreditsWall("No plan", 3, 10)))
    }

    @Test
    fun `legacy draft frames, whole-message results, appended briefs and untyped chunks are handled`() {
        var turn = ChatStreamReducer.reduce(start(), """{"type":"draft.document.start","data":{"document_name":"Plaint","message_id":"m-1"}}""")
        assertThat(turn.message.cards.draftDocument).isEqualTo(DraftDocumentCard("Plaint", "processing", messageId = "m-1"))
        turn =
            ChatStreamReducer.reduce(
                turn,
                """{"type":"draft.document.ready","data":{"document_name":"Plaint","file_url":"/media/u/Plaint.docx",""" +
                    """"template_id":"tmpl-1","can_replace":true,"placeholder_count":3}}""",
            )
        assertThat(turn.message.cards.draftDocument!!.needsSignedUrl).isFalse()
        assertThat(turn.message.cards.draftDocument!!.fileUrl).isEqualTo("/media/u/Plaint.docx")
        turn = ChatStreamReducer.reduce(turn, """{"type":"draft.document.error","message":"Renderer down"}""")
        assertThat(turn.message.cards.draftDocument!!.errorMessage).isEqualTo("Renderer down")
        turn =
            ChatStreamReducer.reduce(
                turn,
                """{"type":"tool.full_message_result","data":{"id":"m-2","role":"assistant","content":"Second answer",""" +
                    """"created_at":"2026-10-13T06:30:00+03:00"}}""",
            )
        turn = ChatStreamReducer.reduce(turn, """{"type":"tool.full_message_result","data":{"id":"m-2","role":"assistant","content":"dup"}}""")
        assertThat(turn.extraMessages.map { it.content }).containsExactly("Second answer")
        turn =
            ChatStreamReducer.reduce(
                turn,
                """{"type":"tool.append_case_brief","data":{"target_message_id":"m-2","case_brief":{"case_id":"c-9","title":"Odinga v IEBC"}}}""",
            )
        assertThat(turn.appendedBriefs["m-2"]!!.single().title).isEqualTo("Odinga v IEBC")
        turn = ChatStreamReducer.reduce(turn, """{"message":"untyped chunk"}""")
        assertThat(turn.message.content).isEqualTo("untyped chunk")
        turn =
            ChatStreamReducer.reduce(
                turn,
                """{"type":"tool.document_bundle_addition_offer","data":{"option_id":"land","option_name":"Land",""" +
                    """"documents":["Witness statement"],"current_bundle":["Plaint"]}}""",
            )
        assertThat(turn.message.cards.bundleAdditionOffer!!.documents).containsExactly("Witness statement")
        turn = ChatStreamReducer.reduce(turn, """{"type":"extracted_case_not_found","message":"No brief for X"}""")
        assertThat(turn.message.progressSteps.last()).isEqualTo("No brief for X")
        assertThat(ChatStreamReducer.reduce(turn, "not json").effects).isEmpty()
    }

    @Test
    fun `the persisted metadata rebuilds every card the thread loader needs`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val body = json.parseToJsonElement(Fixtures.body("ai.conversations.messages.drafting-cards")) as kotlinx.serialization.json.JsonArray
        val offerMessage = body[1] as kotlinx.serialization.json.JsonObject
        val cards = ChatStreamReducer.cardsFromMetadata(offerMessage["metadata"] as kotlinx.serialization.json.JsonObject)
        assertThat(cards.webSources).hasSize(2)
        assertThat(
            cards.webSources[0].snippets,
        ).containsExactly("Twelve years of open, continuous and uninterrupted possession extinguishes the registered owner's title.")
        assertThat(cards.documentBundleOffer!!.suggestedDocuments).hasSize(3)
        assertThat(cards.bundleAdditionOffer!!.proposedBundle).hasSize(3)
        val generated = body[2] as kotlinx.serialization.json.JsonObject
        val cards2 = ChatStreamReducer.cardsFromMetadata(generated["metadata"] as kotlinx.serialization.json.JsonObject)
        assertThat(cards2.documentGeneration!!.status).isEqualTo("complete")
        assertThat(cards2.draftDocument!!.templateId).isEqualTo("tmpl-9e8f7a6b")
        val analysis = (
            json.parseToJsonElement(
                Fixtures.body("ai.conversations.messages.submission-analysis"),
            ) as kotlinx.serialization.json.JsonArray
        )[1] as kotlinx.serialization.json.JsonObject
        val cards3 = ChatStreamReducer.cardsFromMetadata(analysis["metadata"] as kotlinx.serialization.json.JsonObject)
        assertThat(cards3.submissionAnalysis!!.casesFound).isEqualTo(3)
        assertThat(cards3.submissionAnalysis!!.verified).isEqualTo(2)
        assertThat(cards3.submissionHandoff!!.sessionId).isEqualTo("sess-koech-1")
        val files = (
            json.parseToJsonElement(
                Fixtures.body("ai.conversations.messages.with-files"),
            ) as kotlinx.serialization.json.JsonArray
        )[0] as kotlinx.serialization.json.JsonObject
        assertThat(
            ChatStreamReducer.userFiles(files["metadata"] as kotlinx.serialization.json.JsonObject),
        ).containsExactly("westlands-lease.pdf", "rent-schedule.docx")
    }

    @Test
    fun `the case-search socket registers, reports progress, dedupes individual results and replaces them on the final frame`() {
        assertThat(CaseSearchReducer.joinFrame("search-task-1")).isEqualTo("""{"type":"register_task","task_id":"search-task-1"}""")
        val events = Fixtures.socket("case-search").steps.filterIsInstance<SocketStep.Server>().map { CaseSearchReducer.parse(it.frame) }
        assertThat(events[0]).isEqualTo(CaseSearchEvent.Registered)
        assertThat(events[1]).isEqualTo(CaseSearchEvent.Progress("Searching Kenya Law"))
        var results = emptyList<CaseBrief>()
        events.filterIsInstance<CaseSearchEvent.IndividualResult>().forEach { results = CaseSearchReducer.merge(results, it.brief) }
        assertThat(results).hasSize(1)
        assertThat((events.last() as CaseSearchEvent.Results).briefs.map { it.id }).containsExactly("kl-1", "kl-2")
        val unavailable = CaseSearchReducer.parse(
            """{"type":"crawler.database_unavailable","message":"Kenya Law is down","original_keywords":"adverse possession",""" +
                """"alternatives":[{"id":"a1","title":"Use Precedents","mode":"useInternalDb","prefill_prompt":"adverse possession"}]}""",
        )
        assertThat((unavailable as CaseSearchEvent.DatabaseUnavailable).alternatives.single().mode).isEqualTo(ChatMode.INTERNAL_DB)
        assertThat(CaseSearchReducer.parse("""{"type":"crawler.terminated","message":"killed"}""")).isEqualTo(CaseSearchEvent.Failed("killed"))
        assertThat(CaseSearchReducer.parse("""{"type":"error.invalid_message","message":"Invalid message."}""")).isInstanceOf(CaseSearchEvent.Failed::class)
    }

    @Test
    fun `the follow-up socket joins with register_thread and turns progress, final and error frames into events`() {
        assertThat(FollowUpReducer.joinFrame("d4e5")).isEqualTo("""{"type":"register_thread","thread_id":"d4e5"}""")
        val events = Fixtures.socket("chat.follow-up").steps.filterIsInstance<SocketStep.Server>().map { FollowUpReducer.parse(it.frame) }
        assertThat(events[0]).isEqualTo(FollowUpEvent.Progress("Reading the judgment..."))
        assertThat(
            events[1],
        ).isEqualTo(FollowUpEvent.Final("The lease named fourteen days; the court held s. 57 fixed the floor at one full rent period.", "m-9"))
        assertThat(FollowUpReducer.parse("""{"type":"rag_error","content":"boom"}""")).isEqualTo(FollowUpEvent.Failed("boom"))
        assertThat(FollowUpReducer.parse("""{"type":"rag_stream_chunk","content":"partial"}""")).isEqualTo(FollowUpEvent.Progress("partial"))
        assertThat(FollowUpReducer.parse("""{"type":"something_else"}""")).isNull()
        assertThat(FollowUpReducer.THINKING_PLACEHOLDER).isNotNull()
    }
}
