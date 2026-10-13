package com.lexken.lexkenai.feature.chat.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.bodyText
import com.lexken.lexkenai.core.testing.ContractServer.Companion.jsonBody
import com.lexken.lexkenai.core.testing.ContractServer.Companion.multipartParts
import com.lexken.lexkenai.core.testing.ContractServer.Companion.text
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.feature.chat.domain.AnswerFeedback
import com.lexken.lexkenai.feature.chat.domain.ArtifactDownload
import com.lexken.lexkenai.feature.chat.domain.BriefError
import com.lexken.lexkenai.feature.chat.domain.BriefFileFormat
import com.lexken.lexkenai.feature.chat.domain.BriefFileStatus
import com.lexken.lexkenai.feature.chat.domain.BriefRequest
import com.lexken.lexkenai.feature.chat.domain.ChatMode
import com.lexken.lexkenai.feature.chat.domain.ComposerSettings
import com.lexken.lexkenai.feature.chat.domain.ConversationKind
import com.lexken.lexkenai.feature.chat.domain.ExportStatus
import com.lexken.lexkenai.feature.chat.domain.FeedbackRequest
import com.lexken.lexkenai.feature.chat.domain.PlaybookError
import com.lexken.lexkenai.feature.chat.domain.SendError
import com.lexken.lexkenai.feature.chat.domain.SendRequest
import com.lexken.lexkenai.feature.chat.domain.StatuteError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/** T2 for F3: every chat request row (r4–r29, r33, r38–r44) on the wire against the contract server. */
class ChatContractTest {
    private val server = ContractServer()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
    private lateinit var retrofit: Retrofit
    private val thread = "a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10"
    private val brief = "6e2a9c44-1f3b-4d7e-9a8c-2b5d7f1e3c90"

    @BeforeEach
    fun setUp() {
        server.start()
        retrofit = Retrofit.Builder().baseUrl(server.lawrisBaseUrl).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun chat() = RemoteChatRepository(retrofit.create(), json)
    private fun briefs() = RemoteBriefRepository(retrofit.create(), json)
    private fun statutes() = RemoteStatuteRepository(retrofit.create(), json)
    private fun feedback() = RemoteFeedbackRepository(retrofit.create())
    private fun export() = RemoteExportRepository(retrofit.create(), OkHttpClient(), json)
    private fun drafting() = RemoteDraftingRepository(retrofit.create(), json)
    private fun bundles() = RemoteBundleRepository(retrofit.create())

    @Test
    fun `conversations sort newest first and read the title prefixes chat#r4`() = runTest {
        server.serve("ai.conversations.list.kenyan")

        val list = chat().conversations().getOrNull()!!

        assertThat(list.map { it.id.take(1) }).containsExactly("4", "1", "2", "3")
        assertThat(list[0].kind).isEqualTo(ConversationKind.CONTRACT_ANALYSIS)
        assertThat(list[0].title).isEqualTo("Westlands office lease")
        assertThat(list[2].kind).isEqualTo(ConversationKind.INTERNAL_DB)
        assertThat(list[3].title).isEqualTo("Conversation 33333333-3333-4333-8333-333333333333")
        assertThat(list[0].lastUpdatedMillis).isNotNull()
    }

    @Test
    fun `a thread loads with its cards, briefs, progress and attachment names chat#r5`() = runTest {
        server.serve("ai.conversations.messages.case-sources")
        server.serve(
            ContractServer.Host.LAWRIS,
            "GET",
            "/ai/conversations/a7b8c9d0-0000-4a6b-8c7d-9e0f1a2b3c4d/messages/",
            body = com.lexken.lexkenai.core.testing.Fixtures.body("ai.conversations.messages.drafting-artifact"),
        )
        server.serve(
            ContractServer.Host.LAWRIS,
            "GET",
            "/ai/conversations/w/messages/",
            body = com.lexken.lexkenai.core.testing.Fixtures.body("ai.conversations.messages.with-files"),
        )

        val sources = chat().messages("086108f9-b3b5-4893-becc-313a44a8a505").getOrNull()!!
        val artifact = chat().messages("a7b8c9d0-0000-4a6b-8c7d-9e0f1a2b3c4d").getOrNull()!!
        val files = chat().messages("w").getOrNull()!!

        assertThat(sources[0].cards.caseSources.single().court).isEqualTo("Environment and Land Court - Environment and Land Court at Busia")
        assertThat(sources[0].extractedCases.single().id).isEqualTo("8f9ed321-7ce7-4bb2-a098-7cc9a9c18562")
        assertThat(artifact[1].cards.draftingArtifact!!.templateId).isEqualTo("tmpl-4f5e6d7c")
        assertThat(artifact[1].cards.draftDocument!!.needsSignedUrl).isTrue()
        assertThat(artifact[1].progressSteps.first()).isEqualTo("Loading the house style")
        assertThat(files[0].attachmentNames).containsExactly("westlands-lease.pdf", "rent-schedule.docx")
        assertThat(server.requests(Host.LAWRIS, "GET", "/ai/conversations/w/messages/")).hasSize(1)
    }

    @Test
    fun `rename patches the title and delete answers 204 chat#r6 chat#r7`() = runTest {
        server.serve("ai.conversations.patch", "ai.conversations.delete")

        assertThat(chat().rename(thread, "Notice period under the Employment Act")).isEqualTo(Result.Success(Unit))
        assertThat(chat().delete(thread)).isEqualTo(Result.Success(Unit))

        assertThat(
            server.lastRequest(Host.LAWRIS, "PATCH", "/ai/conversations/$thread/").bodyText(),
        ).isEqualTo("""{"title":"Notice period under the Employment Act"}""")
        assertThat(server.requests(Host.LAWRIS, "DELETE", "/ai/conversations/$thread/")).hasSize(1)
    }

    @Test
    fun `the send is a multipart of the sixteen fields plus repeated files, and reads thread_id and task_id chat#r8`() = runTest {
        server.serve("ai.lawris-ai.accepted", "ai.lawris-ai.rag-accepted")
        val attachments = listOf(FileInput("lease.pdf", "application/pdf", "pdf".toByteArray()), FileInput("notes.md", "text/markdown", "md".toByteArray()))

        val general = chat().send(SendRequest("Notice period?", "", attachments = attachments)).getOrNull()!!
        val rag = chat().send(SendRequest("Adverse possession", thread, ComposerSettings(mode = ChatMode.INTERNAL_DB))).getOrNull()!!

        val parts = server.requests(Host.LAWRIS, "POST", "/ai/lawris-ai/")[0].multipartParts()
        assertThat(parts.map { it.name }).containsExactly(
            "prompt",
            "thread_id",
            "case_search",
            "old_case_search",
            "drafting_assistant",
            "transcribe",
            "use_internal_db",
            "use_internal_db_version",
            "drafting_effort",
            "drafting_engine",
            "documents_per_run",
            "drafting_document_type",
            "drafting_output_format",
            "drafting_post_fill",
            "use_web_sources",
            "model_choice",
            "files",
            "files",
        )
        assertThat(parts.text("thread_id")).isEqualTo("")
        assertThat(parts.text("use_internal_db_version")).isEqualTo("v3")
        assertThat(parts.text("use_web_sources")).isEqualTo("true")
        assertThat(parts.filter { it.name == "files" }.map { it.filename }).containsExactly("lease.pdf", "notes.md")
        assertThat(general).isEqualTo(com.lexken.lexkenai.feature.chat.domain.SendAccepted(thread, "celery-7d4e5f6a", 202))
        assertThat(rag.taskId).isNull()
        assertThat(server.requests(Host.LAWRIS, "POST", "/ai/lawris-ai/")[1].multipartParts().text("use_internal_db")).isEqualTo("true")
    }

    @Test
    fun `the send maps the session challenge, the credits wall, validation and server errors chat#r8`() = runTest {
        server.serve("ai.lawris-ai.session-challenge", "ai.lawris-ai.insufficient-credits", "ai.lawris-ai.validation", "ai.lawris-ai.rejected")
        val repository = chat()

        assertThat(repository.send(SendRequest("x", "")).errorOrNull()).isEqualTo(SendError.SessionChallenge)
        val wall = repository.send(SendRequest("x", "")).errorOrNull() as SendError.InsufficientCredits
        assertThat(wall.wall.currentBalance).isEqualTo(4)
        assertThat(wall.wall.requiredCredits).isEqualTo(10)
        assertThat(repository.send(SendRequest("x", "")).errorOrNull()).isEqualTo(SendError.Rejected("Validation Error"))
        assertThat(repository.send(SendRequest("x", "")).errorOrNull()).isEqualTo(SendError.Rejected("An unexpected server error occurred."))
    }

    @Test
    fun `the three brief call sites post their own bodies, and a bad thread is the server's sentence chat#r9 chat#r10 chat#r11`() = runTest {
        server.serve(
            "ai.brief-selected-cases.accepted",
            "ai.brief-selected-cases.accepted",
            "ai.brief-selected-cases.accepted",
            "ai.brief-selected-cases.invalid-thread",
        )
        val repository = briefs()

        repository.briefSelected(BriefRequest.Selection(thread, "adverse possession", listOf("""{"name":"Karanja v Mwaura","citation":"[2019] eKLR"}""")))
        repository.briefSelected(BriefRequest.AutoConfirm(thread, "m-1", listOf("A v B", "C v D"), oldCaseSearch = false))
        repository.briefSelected(BriefRequest.Source(thread, "m-1", """{"case_id":"c-1","title":"Karanja v Mwaura"}"""))
        val refused = repository.briefSelected(BriefRequest.Source("bad", null, """{"case_id":"c-1"}"""))

        val bodies = server.requests(Host.LAWRIS, "POST", "/ai/brief-selected-cases/").map { it.jsonBody().jsonObject }
        assertThat(bodies[0].keys).isEqualTo(setOf("thread_id", "user_query", "selected_cases"))
        assertThat(bodies[0]["selected_cases"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("Karanja v Mwaura")
        assertThat(bodies[1].keys).isEqualTo(setOf("thread_id", "assistant_message_id", "selected_cases", "case_search_new_mode", "old_case_search_old_mode"))
        assertThat(bodies[1]["selected_cases"].toString()).isEqualTo("""["A v B","C v D"]""")
        assertThat(
            bodies[2].keys,
        ).isEqualTo(setOf("thread_id", "assistant_message_id", "selected_cases", "case_search_new_mode", "old_case_search_old_mode", "source_mode"))
        assertThat(bodies[2]["source_mode"]!!.jsonPrimitive.content).isEqualTo("2rah_v2_full_case")
        assertThat(refused.errorOrNull()).isEqualTo(BriefError.Rejected("Invalid 'thread_id' (Conversation ID)."))
    }

    @Test
    fun `the cached lookup sends all three params, hits on a 200 with a summary, and misses on the 204 chat#r12`() = runTest {
        server.serve("ai.case-brief.lookup.hit", "ai.case-brief.lookup.miss")
        val repository = briefs()

        val hit = repository.lookup("[2019] eKLR", "12345", "Karanja v Mwaura").getOrNull()
        val miss = repository.lookup("[2001] eKLR", null, null).getOrNull()

        val query = server.requests(Host.LAWRIS, "GET", "/ai/case-brief/lookup/")[1].url
        assertThat(query.queryParameter("citation")).isEqualTo("[2001] eKLR")
        assertThat(query.queryParameter("case_id")).isEqualTo("")
        assertThat(query.queryParameter("title")).isEqualTo("")
        assertThat(hit!!.id).isEqualTo("6e2a9c44-1f3b-4d7e-9a8c-2b5d7f1e3c90")
        assertThat(hit.summary).isNotNull()
        assertThat(hit.fromCache).isTrue()
        assertThat(miss).isNull()
    }

    @Test
    fun `cancel posts task and conversation, falls back to the briefing cancel, and skip posts the conversation chat#r13 chat#r14`() = runTest {
        server.serve("rag.cancel-task.error", "rag.cancel-briefing", "rag.cancel-briefing")
        val repository = chat()

        assertThat(repository.cancelTask("task-1", thread)).isEqualTo(Result.Success(Unit))
        assertThat(repository.cancelBriefing(thread)).isEqualTo(Result.Success(Unit))

        assertThat(server.lastRequest(Host.LAWRIS, "POST", "/rag/cancel-task/").bodyText()).isEqualTo("""{"task_id":"task-1","conversation_id":"$thread"}""")
        assertThat(server.requests(Host.LAWRIS, "POST", "/rag/cancel-briefing/")).hasSize(2)
        assertThat(server.requests(Host.LAWRIS, "POST", "/rag/cancel-briefing/")[0].bodyText()).isEqualTo("""{"conversation_id":"$thread"}""")
    }

    @Test
    fun `the brief history maps messages and treats the 404 as empty chat#r15`() = runTest {
        server.serve("rag.case.history", "rag.case.history.empty")
        val repository = briefs()

        val history = repository.history(brief).getOrNull()!!
        val empty = repository.history(brief).getOrNull()!!

        assertThat(history.threadId).isEqualTo("d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70")
        assertThat(history.messages.map { it.content.take(9) }).containsExactly("What noti", "The lease")
        assertThat(empty.messages).hasSize(0)
    }

    @Test
    fun `the follow-up posts the query and answers the thread to register chat#r16`() = runTest {
        server.serve("rag.case.follow-up.accepted")

        val threadId = briefs().followUp(brief, "What notice period did the lease require?").getOrNull()

        assertThat(
            server.lastRequest(Host.LAWRIS, "POST", "/rag/case/$brief/follow-up/").bodyText(),
        ).isEqualTo("""{"query":"What notice period did the lease require?"}""")
        assertThat(threadId).isEqualTo("d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70")
    }

    @Test
    fun `the brief file starts with the format in the path, polls 202, lands the named bytes, reports failures chat#r17 chat#r18`() = runTest {
        server.serve("ai.generate-brief.accepted", "ai.brief-status.pending")
        server.serve(
            Host.LAWRIS,
            "GET",
            "/ai/brief-status/brief-export-task-1/",
            200,
            "%PDF-1.4 brief",
            headers = mapOf("Content-Disposition" to """attachment; filename="[Case Brief] Karanja v Mwaura.pdf""""),
        )
        server.serve("ai.brief-status.failed")
        val repository = briefs()

        val taskId = repository.generateFile(brief, BriefFileFormat.PDF).getOrNull()
        val pending = repository.fileStatus(taskId!!, brief, BriefFileFormat.PDF).getOrNull()
        val ready = repository.fileStatus(taskId, brief, BriefFileFormat.PDF).getOrNull() as BriefFileStatus.Ready
        val failed = repository.fileStatus(taskId, brief, BriefFileFormat.PDF).getOrNull()

        assertThat(server.requests(Host.LAWRIS, "GET", "/ai/generate-brief/pdf/$brief/")).hasSize(1)
        assertThat(taskId).isEqualTo("brief-export-task-1")
        assertThat(pending).isEqualTo(BriefFileStatus.Generating)
        assertThat(String(ready.bytes)).isEqualTo("%PDF-1.4 brief")
        assertThat(ready.filename).isEqualTo("[Case Brief] Karanja v Mwaura.pdf")
        assertThat(failed).isEqualTo(BriefFileStatus.Failed("Generated document not found on server after successful task."))
    }

    @Test
    fun `statute explain posts the source verbatim, with the question when asking, and maps detail refusals chat#r19 chat#r20`() = runTest {
        server.serve("ai.statute-explain", "ai.statute-explain.ask", "ai.statute-explain.unavailable")
        val repository = statutes()
        val source = """{"id":"statute_001","title":"Employment Act","citation":"Employment Act, 2007","primary_sections":[{"section_number":"35"}]}"""

        val read = repository.explain(source, null).getOrNull()
        val asked = repository.explain(source, "How long is the notice?").getOrNull()
        val refused = repository.explain(source, null).errorOrNull()

        val bodies = server.requests(Host.LAWRIS, "POST", "/ai/statute-explain/").map { it.jsonBody().jsonObject }
        assertThat(bodies[0].keys).isEqualTo(setOf("source"))
        assertThat(bodies[0]["source"]!!.jsonObject["primary_sections"].toString()).isEqualTo("""[{"section_number":"35"}]""")
        assertThat(bodies[1].keys).isEqualTo(setOf("source", "question"))
        assertThat(read).isEqualTo("Section 57 requires notice of at least one period before a periodic lease ends.")
        assertThat(asked!!.contains("at least one month")).isTrue()
        assertThat(refused).isEqualTo(StatuteError.Rejected("The statute reader is unavailable."))
    }

    @Test
    fun `feedback posts to the no-slash route with the web's keys, like and dislike alike chat#r21 chat#r23`() = runTest {
        server.serve("ai.feedback-ai.like.201", "ai.feedback-ai.400")
        val repository = feedback()

        val liked = repository.send(
            FeedbackRequest(
                "78472b71-2fcb-445d-941c-5cc82636d63a",
                "c2a29717-c73c-47a9-9eee-8f041efc0793",
                AnswerFeedback.LIKE,
                "Is a summary dismissal unfair?",
                "Under section 41…",
            ),
        )
        val disliked = repository.send(FeedbackRequest("78472b71", "c2a29717", AnswerFeedback.DISLIKE, "q", "a", details = "<p>Wrong section.</p>"))

        val requests = server.requests(Host.LAWRIS, "POST", "/ai/feedback-ai")
        assertThat(requests[0].url.encodedPath).isEqualTo("/api/ai/feedback-ai")
        assertThat(requests[0].jsonBody().jsonObject.keys).isEqualTo(setOf("conversation", "response_id", "feedback_type", "prompt", "answer"))
        assertThat(requests[1].jsonBody().jsonObject["details"]!!.jsonPrimitive.content).isEqualTo("<p>Wrong section.</p>")
        assertThat(requests[1].jsonBody().jsonObject["feedback_type"]!!.jsonPrimitive.content).isEqualTo("dislike")
        assertThat(liked).isEqualTo(Result.Success(Unit))
        assertThat(disliked.errorOrNull()).isEqualTo(DataError.Network.UNKNOWN)
    }

    @Test
    fun `the Word export starts, polls pending, success and the hidden failure, fetches without a bearer chat#r24 chat#r25 chat#r26 chat#r27`() = runTest {
        server.serve(
            "ai.format-text-to-word.accepted",
            "ai.format-task-status.pending",
            "ai.format-task-status.success",
            "ai.format-task-status.failure",
            "ai.format-text-to-word.no-conversation",
        )
        server.serve(Host.LAWRIS, "GET", "/media/u-1/FORMATTED/Patched_Submission.docx", 200, "PK docx bytes")
        val repository = export()

        val taskId = repository.formatToWord("Under section 35…", thread, "LexKen AI Response - 13/10/2026").getOrNull()
        val pending = repository.status(taskId!!).getOrNull()
        val success = repository.status(taskId).getOrNull()
        val failure = repository.status(taskId).getOrNull()
        val refused = repository.formatToWord("x", "nope", "n").errorOrNull()
        val file = repository.download(
            server.lawris.url("/media/u-1/FORMATTED/Patched_Submission.docx").toString(),
            "lexken-ai-response-2026-10-13.docx",
        ).getOrNull()!!

        assertThat(
            server.lastRequest(Host.LAWRIS, "POST", "/ai/format-text-to-word/").bodyText(),
        ).isEqualTo("""{"raw_text":"x","conversation_id":"nope","document_name":"n"}""")
        assertThat(taskId).isEqualTo("fmt-task-1")
        assertThat(pending).isEqualTo(ExportStatus.Pending)
        assertThat(success).isEqualTo(ExportStatus.Success("/media/u-1/FORMATTED/Patched_Submission.docx"))
        assertThat(failure).isEqualTo(ExportStatus.Failure("AI formatting error."))
        assertThat(refused!!).isInstanceOf(com.lexken.lexkenai.feature.chat.domain.ExportError.Failed::class)
        assertThat(String(file.bytes)).isEqualTo("PK docx bytes")
        assertThat(file.filename).isEqualTo("lexken-ai-response-2026-10-13.docx")
        assertThat(server.lastRequest(Host.LAWRIS, "GET", "/media/u-1/FORMATTED/Patched_Submission.docx").headers["Authorization"]).isNull()
    }

    @Test
    fun `the pro artifact download goes through the doubled api prefix and reads the signed url, or the bytes when streamed chat#r28 chat#r29`() = runTest {
        server.serve("api.drafting.artifacts.download")
        server.serve(
            Host.LAWRIS,
            "GET",
            "/api/drafting/artifacts/art-local/download/",
            200,
            "PK local docx",
            headers = mapOf(
                "Content-Type" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Content-Disposition" to """attachment; filename="Plaint.docx"""",
            ),
        )
        val repository = export()

        val signed = repository.signedUrl("/api/drafting/artifacts/art-7d4e/download/").getOrNull() as ArtifactDownload.Url
        val streamed = repository.signedUrl("/api/drafting/artifacts/art-local/download/").getOrNull() as ArtifactDownload.Bytes

        assertThat(
            server.lastRequest(Host.LAWRIS, "GET", "/api/drafting/artifacts/art-7d4e/download/").url.encodedPath,
        ).isEqualTo("/api/api/drafting/artifacts/art-7d4e/download/")
        assertThat(signed.url.startsWith("https://lexkenbucket.sfo3.digitaloceanspaces.com/")).isTrue()
        assertThat(signed.filename).isEqualTo("Demand_letter_Ramoya.docx")
        assertThat(streamed.document.filename).isEqualTo("Plaint.docx")
    }

    @Test
    fun `adding documents to a bundle posts the option and names and reads the outcome chat#r33`() = runTest {
        server.serve("ai.document-bundles.add-documents.200", "ai.document-bundles.add-documents.not-applied")
        val repository = bundles()

        val applied = repository.addDocuments("land-dispute", listOf("Witness statement — Ramoya")).getOrNull()!!
        val notApplied = repository.addDocuments("suits:suit_9", listOf("x")).getOrNull()!!

        assertThat(
            server.requests(Host.LAWRIS, "POST", "/ai/document-bundles/add-documents/")[0].bodyText(),
        ).isEqualTo("""{"option_id":"land-dispute","documents":["Witness statement — Ramoya"]}""")
        assertThat(applied.added).containsExactly("Witness statement — Ramoya")
        assertThat(applied.bundleName).isEqualTo("Land dispute bundle")
        assertThat(notApplied.added).hasSize(0)
    }

    @Test
    fun `the drafting registry is read once, cached, and falls back to the ten bundled types on error chat#r38`() = runTest {
        server.serve(Host.LAWRIS, "GET", "/api/drafting/document-types/", 503, """{"detail":"down"}""")
        server.serve("api.drafting.document-types")
        val repository = drafting()

        val fallback = repository.registry()
        val live = repository.registry()
        val cached = repository.registry()

        assertThat(fallback.fromFallback).isTrue()
        assertThat(fallback.documentTypes).hasSize(10)
        assertThat(fallback.documentTypes.map { it.key }.first()).isEqualTo("land_sale_agreement")
        assertThat(live.fromFallback).isFalse()
        assertThat(live.canEditPlaybooks).isTrue()
        assertThat(live.outputFormats).containsExactly("text", "docx", "both")
        assertThat(cached).isEqualTo(live)
        assertThat(server.requests(Host.LAWRIS, "GET", "/api/drafting/document-types/")).hasSize(2)
    }

    @Test
    fun `playbooks load, save, conflict, forbid, reset, list and restore chat#r39 chat#r40 chat#r41 chat#r42 chat#r43 chat#r44`() = runTest {
        server.serve(
            "api.drafting.playbooks.lease",
            "api.drafting.playbooks.lease.saved",
            "api.drafting.playbooks.lease.conflict",
            "api.drafting.playbooks.lease.forbidden",
            "api.drafting.playbooks.lease.reset",
            "api.drafting.playbooks.lease.versions",
            "api.drafting.playbooks.lease.version",
            "api.drafting.playbooks.lease.restored",
        )
        val repository = drafting()

        val loaded = repository.playbook("lease_agreement").getOrNull()!!
        val saved = repository.savePlaybook("lease_agreement", "# Lease agreement\n\nAlways cite the Land Act 2012.", 3).getOrNull()!!
        val conflict = repository.savePlaybook("lease_agreement", "x", 3).errorOrNull()
        val forbidden = repository.savePlaybook("lease_agreement", "x", 3).errorOrNull()
        val reset = repository.resetPlaybook("lease_agreement").getOrNull()!!
        val versions = repository.versions("lease_agreement").getOrNull()!!
        val version = repository.version("lease_agreement", "v-3").getOrNull()!!
        val restored = repository.restore("lease_agreement", "v-1").getOrNull()!!

        assertThat(loaded.version).isEqualTo(3L)
        assertThat(loaded.canEdit).isTrue()
        assertThat(
            server.requests(Host.LAWRIS, "PUT", "/api/drafting/playbooks/lease_agreement/")[0].bodyText(),
        ).isEqualTo("""{"content":"# Lease agreement\n\nAlways cite the Land Act 2012.","expected_version":3}""")
        assertThat(
            server.requests(Host.LAWRIS, "PUT", "/api/drafting/playbooks/lease_agreement/")[0].url.encodedPath,
        ).isEqualTo("/api/api/drafting/playbooks/lease_agreement/")
        assertThat(saved.version).isEqualTo(4L)
        assertThat(conflict).isEqualTo(PlaybookError.Conflict)
        assertThat(
            forbidden,
        ).isEqualTo(PlaybookError.Rejected("Only a firm administrator can change the firm's drafting playbooks. You can still view them and draft with them."))
        assertThat(reset.isCustomized).isFalse()
        assertThat(versions.map { it.changeType }).containsExactly("edit", "seed")
        assertThat(versions[0].editedBy).isEqualTo("Wanjiru Mwangi")
        assertThat(versions[0].additions).isEqualTo(4)
        assertThat(version.diff!!.startsWith("@@")).isTrue()
        assertThat(restored.version).isEqualTo(6L)
        assertThat(server.requests(Host.LAWRIS, "POST", "/api/drafting/playbooks/lease_agreement/versions/v-1/restore/")).hasSize(1)
    }
}
