package com.lexken.lexkenai.feature.chat.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.network.JobTracker
import com.lexken.lexkenai.core.network.PollingEngine
import com.lexken.lexkenai.core.network.SocketEvent
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.Fixtures
import com.lexken.lexkenai.core.testing.RecordingSocketClient
import com.lexken.lexkenai.core.testing.SocketStep
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** T3 for F3: the send turn over a scripted socket, cancel, the follow-up, the brief file and export polls, the source-card pipeline. */
class ChatFlowsTest {
    private val thread = "a3f1c9e2-7b44-4d6e-9f1a-2c8e5b7d3a10"

    private class ScriptedChat(private val accepted: Result<SendAccepted, SendError>) : ChatRepository {
        val sent = mutableListOf<SendRequest>()
        val cancels = mutableListOf<String>()
        var persisted: List<ChatMessage> = emptyList()
        var messageReads = 0

        override suspend fun conversations() = Result.Success(emptyList<Conversation>())

        override suspend fun messages(threadId: String): Result<List<ChatMessage>, DataError> = Result.Success(persisted).also { messageReads++ }

        override suspend fun rename(threadId: String, title: String): EmptyResult<DataError> = Result.Success(Unit)

        override suspend fun delete(threadId: String): EmptyResult<DataError> = Result.Success(Unit)

        override suspend fun send(request: SendRequest): Result<SendAccepted, SendError> = accepted.also { sent += request }

        override suspend fun cancelTask(taskId: String?, conversationId: String): EmptyResult<DataError> = Result.Success(Unit).also {
            cancels +=
                "task:$taskId/$conversationId"
        }

        override suspend fun cancelBriefing(conversationId: String): EmptyResult<DataError> = Result.Success(Unit).also {
            cancels += "briefing:$conversationId"
        }
    }

    private fun serverFrames(fixture: String) = Fixtures.socket(fixture).steps.filterIsInstance<SocketStep.Server>().map { it.frame }

    @Test
    fun `a general turn adopts the 202, the socket frames shape the turn, stream_end ends it and the poll's record follows`() = runTest {
        val chat = ScriptedChat(Result.Success(SendAccepted(thread, "task-1", 202)))
        chat.persisted = listOf(ChatMessage("u", MessageRole.USER, "q"), ChatMessage("srv-1", MessageRole.ASSISTANT, "Under section 35…"))
        val sockets = RecordingSocketClient()
        val useCase = SendTurnUseCase(chat, JobTracker(PollingEngine(), sockets), "https://stage.lexken.ai/api/", "LexKen AI", now = { 1_000L })
        val events = mutableListOf<TurnEvent>()

        val job = launch { useCase(SendRequest("Notice period?", "")).collect { events += it } }
        advanceTimeBy(10)
        serverFrames("chat.full-turn").forEach { sockets.frame("/ws/ai/lawris-ai/", it) }
        advanceTimeBy(2_000)
        job.join()

        assertThat(chat.sent.single().threadId).isEqualTo("")
        assertThat(events[0]).isEqualTo(TurnEvent.Accepted(SendAccepted(thread, "task-1", 202)))
        assertThat(sockets.connections.single().openFrames).containsExactly("""{"thread_id":"$thread"}""")
        val updates = events.filterIsInstance<TurnEvent.Updated>()
        assertThat(updates.first().turn.message.content).isEqualTo("Processing query...")
        assertThat(updates.last().turn.ended).isTrue()
        assertThat(updates.last().turn.message.content.startsWith("Under section 35(1)(c)")).isTrue()
        assertThat(events.filterIsInstance<TurnEvent.Effect>().map { it.effect }).containsExactly(TurnEffect.CloseSocket)
        assertThat(events.last()).isInstanceOf(TurnEvent.ThreadReloaded::class)
        assertThat((events.last() as TurnEvent.ThreadReloaded).messages.last().id).isEqualTo("srv-1")
    }

    @Test
    fun `a refused send ends the flow with the error and opens no socket, and a case-search send opens no chat socket`() = runTest {
        val sockets = RecordingSocketClient()
        val refused =
            SendTurnUseCase(ScriptedChat(Result.Failure(SendError.SessionChallenge)), JobTracker(PollingEngine(), sockets), "https://x/api/", "LexKen AI")
        val search =
            SendTurnUseCase(
                ScriptedChat(Result.Success(SendAccepted(thread, "search-task", 202))),
                JobTracker(PollingEngine(), sockets),
                "https://x/api/",
                "LexKen AI",
            )

        assertThat(refused(SendRequest("x", "")).toList()).containsExactly(TurnEvent.Rejected(SendError.SessionChallenge))
        assertThat(refused(SendRequest("   ", "")).toList().single()).isInstanceOf(TurnEvent.Rejected::class)
        assertThat(
            refused(SendRequest("x", "", attachments = listOf(com.lexken.lexkenai.core.model.FileInput("a.exe", null, ByteArray(1))))).toList().single(),
        ).isInstanceOf(TurnEvent.Rejected::class)
        assertThat(
            search(SendRequest("x", "", ComposerSettings(mode = ChatMode.CASE_SEARCH))).toList(),
        ).containsExactly(TurnEvent.Accepted(SendAccepted(thread, "search-task", 202)))
        assertThat(sockets.connections).hasSize(0)
    }

    @Test
    fun `a socket that closes early ends the turn as not streaming and the thread is re-read`() = runTest {
        val chat = ScriptedChat(Result.Success(SendAccepted(thread, "task-1", 202)))
        chat.persisted = listOf(ChatMessage("srv-1", MessageRole.ASSISTANT, "persisted"))
        val sockets = RecordingSocketClient()
        val useCase = SendTurnUseCase(chat, JobTracker(PollingEngine(), sockets), "https://x/api/", "LexKen AI")
        val events = mutableListOf<TurnEvent>()

        val job = launch { useCase(SendRequest("x", thread)).collect { events += it } }
        advanceTimeBy(10)
        sockets.push("/ws/ai/lawris-ai/", SocketEvent.Closed(1006, null))
        advanceTimeBy(2_000)
        job.join()

        assertThat(events.filterIsInstance<TurnEvent.SocketClosed>()).containsExactly(TurnEvent.SocketClosed(1006))
        assertThat(events.filterIsInstance<TurnEvent.Updated>().last().turn.streaming).isFalse()
    }

    @Test
    fun `cancel revokes the task on the backend thread and skip cancels the briefing`() = runTest {
        val chat = ScriptedChat(Result.Success(SendAccepted(thread, "task-1", 202)))

        CancelTurnUseCase(chat)("task-1", thread)
        CancelTurnUseCase(chat)("task-1", null)
        SkipBriefingUseCase(chat)(thread)

        assertThat(chat.cancels).containsExactly("task:task-1/$thread", "briefing:$thread")
    }

    private class ScriptedBriefs : BriefRepository {
        var lookupHit: CaseBrief? = null
        val briefed = mutableListOf<BriefRequest>()
        var fileReads = 0
        var fileStatuses: List<BriefFileStatus> =
            listOf(BriefFileStatus.Generating, BriefFileStatus.Generating, BriefFileStatus.Ready("pdf".toByteArray(), "case_brief.pdf"))

        override suspend fun briefSelected(request: BriefRequest): EmptyResult<BriefError> = Result.Success(Unit).also { briefed += request }

        override suspend fun lookup(citation: String?, caseId: String?, title: String?): Result<CaseBrief?, DataError> = Result.Success(lookupHit)

        override suspend fun history(caseId: String) = Result.Success(CaseBriefHistory(null, emptyList()))

        override suspend fun followUp(caseId: String, query: String): Result<String, BriefError> = Result.Success("d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70")

        override suspend fun generateFile(caseId: String, format: BriefFileFormat): Result<String, BriefError> = Result.Success("brief-export-task-1")

        override suspend fun fileStatus(taskId: String, caseId: String, format: BriefFileFormat): Result<BriefFileStatus, DataError> =
            Result.Success(fileStatuses[minOf(fileReads, fileStatuses.lastIndex)]).also { fileReads++ }
    }

    @Test
    fun `a source card opens the cached brief when the lookup hits, else briefs over a dedicated socket until the batch completes`() = runTest {
        val briefs = ScriptedBriefs()
        val sockets = RecordingSocketClient()
        val useCase = OpenSourceCardUseCase(briefs, sockets, "https://x/api/")
        val source = CaseSourceCard("c-1", "Karanja v Mwaura", "[2019] eKLR", rawJson = """{"case_id":"c-1","title":"Karanja v Mwaura"}""")

        briefs.lookupHit = CaseBrief("c-1", "Karanja v Mwaura", "[2019] eKLR", "summary", null, null, null, null, fromCache = true)
        assertThat(useCase(source, thread, "m-1")).isInstanceOf(SourceCardOutcome.CachedBrief::class)

        briefs.lookupHit = null
        assertThat(useCase(source, null, "m-1")).isEqualTo(SourceCardOutcome.Failed(BriefError.ConversationInitializing))
        val briefing = useCase(source, thread, "m-1") as SourceCardOutcome.Briefing
        assertThat(briefs.briefed.last()).isInstanceOf(BriefRequest.Source::class)
        val received = mutableListOf<CaseBrief>()
        val job = launch { briefing.frames.collect { received += it } }
        advanceTimeBy(10)
        serverFrames("chat.source-brief").forEach { sockets.frame("/ws/ai/lawris-ai/", it) }
        job.join()
        assertThat(received.single().title).isEqualTo("Karanja v Mwaura")
        assertThat(sockets.connections.last().openFrames).containsExactly("""{"thread_id":"$thread"}""")
    }

    @Test
    fun `the follow-up asks, joins with register_thread, and ends on the final frame`() = runTest {
        val sockets = RecordingSocketClient()
        val useCase = FollowUpUseCase(ScriptedBriefs(), sockets, "https://x/api/")
        val events = mutableListOf<FollowUpEvent>()

        val job = launch { useCase("c-1", "What notice period?").collect { events += it } }
        advanceTimeBy(10)
        serverFrames("chat.follow-up").forEach { sockets.frame("/ws/ai/lawris-ai/", it) }
        job.join()

        assertThat(sockets.connections.single().openFrames).containsExactly("""{"type":"register_thread","thread_id":"d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70"}""")
        assertThat(events.map { it::class.simpleName }).containsExactly("Progress", "Progress", "Final")
        assertThat((events.last() as FollowUpEvent.Final).messageId).isEqualTo("m-9")
    }

    @Test
    fun `the brief file polls every three seconds until the bytes land`() = runTest {
        val briefs = ScriptedBriefs()

        val statuses = GenerateBriefFileUseCase(briefs, PollingEngine())("c-1", BriefFileFormat.PDF).toList()

        assertThat(statuses.map { it::class.simpleName }).containsExactly("Generating", "Ready")
        assertThat(briefs.fileReads).isEqualTo(3)
        assertThat(testScheduler.currentTime).isEqualTo(6_000L)
    }

    private class ScriptedExport(private val statuses: List<ExportStatus>) : ExportRepository {
        var reads = 0
        var downloaded: String? = null

        override suspend fun formatToWord(rawText: String, conversationId: String, documentName: String): Result<String, ExportError> = Result.Success(
            "fmt-task-1",
        )

        override suspend fun status(taskId: String): Result<ExportStatus, DataError> = Result.Success(statuses[minOf(reads, statuses.lastIndex)]).also {
            reads++
        }

        override suspend fun download(fileUrl: String, filename: String): Result<ExportedDocument, DataError> =
            Result.Success(ExportedDocument("docx".toByteArray(), filename)).also { downloaded = fileUrl }

        override suspend fun signedUrl(downloadUrl: String): Result<ArtifactDownload, DataError> = Result.Success(
            ArtifactDownload.Url("https://signed/$downloadUrl", null),
        )
    }

    @Test
    fun `the Word export polls five seconds apart, downloads on success, fails on a failure, and times out after thirty attempts`() = runTest {
        val success = ScriptedExport(listOf(ExportStatus.Pending, ExportStatus.Pending, ExportStatus.Success("/media/u/x.docx")))
        val exported = ExportAnswerToWordUseCase(
            success,
            PollingEngine(),
            "LexKen AI",
            "https://stage.lexken.ai",
        )("text", thread, "13/10/2026", "2026-10-13").getOrNull()!!
        assertThat(exported.filename).isEqualTo("lexken-ai-response-2026-10-13.docx")
        // The live server answers a host-root path with spaces: it is resolved on the media host, never under /api.
        assertThat(success.downloaded).isEqualTo("https://stage.lexken.ai/media/u/x.docx")
        assertThat(success.reads).isEqualTo(3)
        assertThat(testScheduler.currentTime).isEqualTo(10_000L)

        val failed =
            ExportAnswerToWordUseCase(
                ScriptedExport(listOf(ExportStatus.Failure("AI formatting error."))),
                PollingEngine(),
                "LexKen AI",
                "https://stage.lexken.ai",
            )("text", thread, "d", "i")
        assertThat(failed).isEqualTo(Result.Failure(ExportError.Failed("AI formatting error.")))

        val forever = ScriptedExport(listOf(ExportStatus.Pending))
        val timedOut = ExportAnswerToWordUseCase(forever, PollingEngine(), "LexKen AI", "https://stage.lexken.ai")("text", thread, "d", "i")
        assertThat(timedOut).isEqualTo(Result.Failure(ExportError.TimedOut))
        assertThat(forever.reads).isEqualTo(30)

        assertThat(
            ExportAnswerToWordUseCase(success, PollingEngine(), "LexKen AI", "https://stage.lexken.ai")("text", null, "d", "i"),
        ).isEqualTo(Result.Failure(ExportError.NoActiveConversation))
    }

    @Test
    fun `a pro draft opens through the signed url and a legacy draft through the media host`() = runTest {
        val useCase = OpenDraftDocumentUseCase(ScriptedExport(emptyList()), "https://stage.lexken.ai")

        val pro = useCase(DraftDocumentCard("Demand letter", "ready", fileUrl = "/api/drafting/artifacts/art-1/download/", needsSignedUrl = true)).getOrNull()
        val legacy = useCase(DraftDocumentCard("Plaint", "ready", fileUrl = "/media/u/Plaint.docx")).getOrNull()

        assertThat(pro).isEqualTo(ArtifactDownload.Url("https://signed//api/drafting/artifacts/art-1/download/", null))
        assertThat((legacy as ArtifactDownload.Bytes).document.filename).isEqualTo("Plaint")
    }

    @Test
    fun `the generation confirm is a normal turn carrying the synthesised prompt, and the playbook skill reshapes the composer`() = runTest {
        val chat = ScriptedChat(Result.Failure(SendError.SessionChallenge))
        val send = SendTurnUseCase(chat, JobTracker(PollingEngine(), RecordingSocketClient()), "https://x/api/", "LexKen AI")
        val offer = DocumentBundleOffer("suits:suit_2", "Breach of contract", listOf(BundleDocument("Demand Letter", true), BundleDocument("Plaint", true)))

        ConfirmDocumentGenerationUseCase(send)(offer, listOf("Demand Letter", "Plaint"), thread, ComposerSettings()).toList()

        assertThat(chat.sent.single().prompt).isEqualTo("Yes — please generate these documents for the Breach of contract bundle: Demand Letter, Plaint.")
        assertThat(chat.sent.single().threadId).isEqualTo(thread)

        val registry = object : DraftingRepository {
            override suspend fun registry() = DraftingRegistry(
                listOf(DocumentType("lease_agreement", "lease_agreement", "Lease agreement", true)),
                listOf("docx"),
                "docx",
                true,
                false,
            )
            override suspend fun playbook(documentType: String) = Result.Failure(PlaybookError.Conflict)
            override suspend fun savePlaybook(documentType: String, content: String, expectedVersion: Long?) = Result.Failure(PlaybookError.Conflict)
            override suspend fun resetPlaybook(documentType: String) = Result.Failure(PlaybookError.Conflict)
            override suspend fun versions(documentType: String) = Result.Failure(PlaybookError.Conflict)
            override suspend fun version(documentType: String, versionId: String) = Result.Failure(PlaybookError.Conflict)
            override suspend fun restore(documentType: String, versionId: String) = Result.Failure(PlaybookError.Conflict)
        }
        val reshaped = LoadPlaybookSkillUseCase(registry)(ComposerSettings(), PlaybookSkill("Lease agreement", "lease-agreement", null))
        assertThat(reshaped.mode).isEqualTo(ChatMode.DRAFTING_ASSISTANT)
        assertThat(reshaped.draftingDocumentType).isEqualTo("lease_agreement")
        assertThat(ProDrafterRules.isProDrafter(reshaped)).isTrue()
    }
}
