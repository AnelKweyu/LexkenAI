package com.lexken.lexkenai.feature.chat.domain

import com.lexken.lexkenai.core.network.JobEvent
import com.lexken.lexkenai.core.network.JobTracker
import com.lexken.lexkenai.core.network.PollEvent
import com.lexken.lexkenai.core.network.PollSchedule
import com.lexken.lexkenai.core.network.PollingEngine
import com.lexken.lexkenai.core.network.ReconnectPolicy
import com.lexken.lexkenai.core.network.SocketClient
import com.lexken.lexkenai.core.network.SocketEvent
import com.lexken.lexkenai.core.network.SocketSubscription
import com.lexken.lexkenai.core.network.SocketUrls
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile

// ── Repository contracts ────────────────────────────────────────────────────

interface ChatRepository {
    /** `GET ai/conversations/` (r4), sorted newest first with the title prefixes read. */
    suspend fun conversations(): Result<List<Conversation>, DataError>

    /** `GET ai/conversations/:id/messages/` (r5) with the cards rebuilt from `metadata`. */
    suspend fun messages(threadId: String): Result<List<ChatMessage>, DataError>

    /** `PATCH ai/conversations/:id/ {title}` (r6). */
    suspend fun rename(threadId: String, title: String): EmptyResult<DataError>

    /** `DELETE ai/conversations/:id/` (r7). */
    suspend fun delete(threadId: String): EmptyResult<DataError>

    /** `POST ai/lawris-ai/` multipart (r8), through the AI-session interceptor. */
    suspend fun send(request: SendRequest): Result<SendAccepted, SendError>

    /** `POST rag/cancel-task/` (r13), falling back to `rag/cancel-briefing/` on error. */
    suspend fun cancelTask(taskId: String?, conversationId: String): EmptyResult<DataError>

    /** `POST rag/cancel-briefing/` (r14). */
    suspend fun cancelBriefing(conversationId: String): EmptyResult<DataError>
}

interface BriefRepository {
    /** `POST ai/brief-selected-cases/` (r9–r11), results arrive on the thread socket. */
    suspend fun briefSelected(request: BriefRequest): EmptyResult<BriefError>

    /** `GET ai/case-brief/lookup/?citation&case_id&title` (r12): a brief on 200 + `summary`, null otherwise. */
    suspend fun lookup(citation: String?, caseId: String?, title: String?): Result<CaseBrief?, DataError>

    /** `GET rag/case/:id/history/` (r15): 404 = empty. */
    suspend fun history(caseId: String): Result<CaseBriefHistory, DataError>

    /** `POST rag/case/:id/follow-up/ {query}` (r16) → the thread id to register on the socket. */
    suspend fun followUp(caseId: String, query: String): Result<String, BriefError>

    /** `GET ai/generate-brief/:format/:id/` (r17) → task id. */
    suspend fun generateFile(caseId: String, format: BriefFileFormat): Result<String, BriefError>

    /** `GET ai/brief-status/:taskId/` (r18): 202 = generating, 200 = the file. */
    suspend fun fileStatus(taskId: String, caseId: String, format: BriefFileFormat): Result<BriefFileStatus, DataError>
}

sealed interface BriefError : com.lexken.lexkenai.core.result.Error {
    /** `error.response.data.error` or the call site's fallback sentence. */
    data class Rejected(val message: String) : BriefError

    data object ConversationInitializing : BriefError

    data class Transport(val error: DataError) : BriefError
}

interface StatuteRepository {
    /** `POST ai/statute-explain/ {source[, question]}` (r19/r20) → `answer`. */
    suspend fun explain(sourceJson: String, question: String?): Result<String, StatuteError>
}

sealed interface StatuteError : com.lexken.lexkenai.core.result.Error {
    data class Rejected(val message: String) : StatuteError

    data class Transport(val error: DataError) : StatuteError
}

interface FeedbackRepository {
    /** `POST ai/feedback-ai` (r21/r23), no trailing slash; 201. */
    suspend fun send(request: FeedbackRequest): EmptyResult<DataError>
}

interface ExportRepository {
    /** `POST ai/format-text-to-word/` (r24/r27) → task id. */
    suspend fun formatToWord(rawText: String, conversationId: String, documentName: String): Result<String, ExportError>

    /** `GET ai/format-task-status/:id/` (r25). */
    suspend fun status(taskId: String): Result<ExportStatus, DataError>

    /** `GET <host root>/<file_url>` with no bearer (r26/r29). */
    suspend fun download(fileUrl: String, filename: String): Result<ExportedDocument, DataError>

    /** `GET <download_url>` relative to the API base (r28) → the signed `url`, or the bytes when the server streams. */
    suspend fun signedUrl(downloadUrl: String): Result<ArtifactDownload, DataError>
}

sealed interface ArtifactDownload {
    data class Url(val url: String, val filename: String?) : ArtifactDownload

    data class Bytes(val document: ExportedDocument) : ArtifactDownload
}

interface DraftingRepository {
    /** `GET api/drafting/document-types/` (r38), cached once per process, bundled fallback on error. */
    suspend fun registry(): DraftingRegistry

    suspend fun playbook(documentType: String): Result<Playbook, PlaybookError>

    suspend fun savePlaybook(documentType: String, content: String, expectedVersion: Long?): Result<Playbook, PlaybookError>

    suspend fun resetPlaybook(documentType: String): Result<Playbook, PlaybookError>

    suspend fun versions(documentType: String): Result<List<PlaybookVersion>, PlaybookError>

    suspend fun version(documentType: String, versionId: String): Result<PlaybookVersionDetail, PlaybookError>

    suspend fun restore(documentType: String, versionId: String): Result<Playbook, PlaybookError>
}

interface BundleRepository {
    /** `POST ai/document-bundles/add-documents/` (r33); always 200 with a status. */
    suspend fun addDocuments(optionId: String, documents: List<String>): Result<BundleAdditionOutcome, DataError>
}

// ── Use cases: conversations ────────────────────────────────────────────────

class LoadConversationsUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(): Result<List<Conversation>, DataError> = repository.conversations()
}

class LoadThreadUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(threadId: String): Result<List<ChatMessage>, DataError> = repository.messages(threadId)
}

/** Rule r6: trimmed, non-empty; optimistic on the caller's side with revert on failure. */
class RenameConversationUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(threadId: String, title: String): EmptyResult<DataError> {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return Result.Failure(DataError.Local.UNKNOWN)
        return repository.rename(threadId, trimmed)
    }
}

class DeleteConversationUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(threadId: String): EmptyResult<DataError> = repository.delete(threadId)
}

// ── Use cases: the turn ─────────────────────────────────────────────────────

/** What the send use case emits while a turn runs. */
sealed interface TurnEvent {
    /** The 202 came back: the ids to adopt (rule 8). */
    data class Accepted(val accepted: SendAccepted) : TurnEvent

    /** The turn as the socket shaped it so far. */
    data class Updated(val turn: ChatTurn) : TurnEvent

    /** A side effect to run (wall, reload, auto-open…). */
    data class Effect(val effect: TurnEffect) : TurnEvent

    /** The socket closed; the web re-reads the list a second later (rule 35). */
    data class SocketClosed(val code: Int?) : TurnEvent

    /** The send itself failed (rule 9's `err-*` row and the Retry queue). */
    data class Rejected(val error: SendError) : TurnEvent

    /** The thread re-read from REST (after `submission.complete`, or the poll's record). */
    data class ThreadReloaded(val messages: List<ChatMessage>) : TurnEvent
}

/**
 * `handlePromptSubmit` + the chat socket: validate, send, adopt the ids, then run the turn's
 * socket beside the thread poll (`JobTracker.track` with the CHAT schedule — the poll is the
 * record, the socket the accelerator) and reduce every frame. The flow ends when the reducer
 * says the turn ended, the socket closed, or the poll's record shows the answer persisted.
 */
class SendTurnUseCase(
    private val repository: ChatRepository,
    private val tracker: JobTracker,
    private val apiBaseUrl: String,
    private val brand: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    operator fun invoke(request: SendRequest): Flow<TurnEvent> = flow {
        if (request.prompt.isBlank()) {
            emit(TurnEvent.Rejected(SendError.Rejected("Enter a prompt.")))
            return@flow
        }
        AttachmentRules.firstRejected(request.attachments)?.let {
            emit(TurnEvent.Rejected(SendError.Rejected("$it is not a supported attachment.")))
            return@flow
        }
        val accepted = when (val sent = repository.send(request)) {
            is Result.Failure -> {
                emit(TurnEvent.Rejected(sent.error))
                return@flow
            }

            is Result.Success -> {
                sent.data
            }
        }
        emit(TurnEvent.Accepted(accepted))
        val threadId = accepted.threadId?.takeIf { it.isNotBlank() }
        if (request.flow == TurnFlow.CASE_SEARCH || threadId == null) return@flow

        var turn = ChatStreamReducer.start(request.flow, brand, OptimisticMessages.acceptedAssistant(now(), request.flow, brand))
        emit(TurnEvent.Updated(turn))
        var done = false
        // The poll is the record: once the socket has ended the turn (or dropped), the next read of the thread settles it.
        tracker.track(
            schedule = PollSchedule.CHAT,
            isTerminal = { _: List<ChatMessage> -> turn.ended },
            socket = SocketSubscription.chat(apiBaseUrl, threadId),
        ) { repository.messages(threadId) }
            .takeWhile { !done }
            .collect { event ->
                when (event) {
                    is JobEvent.Live -> {
                        turn = ChatStreamReducer.reduce(turn, event.frame)
                        emit(TurnEvent.Updated(turn))
                        turn.effects.forEach { emit(TurnEvent.Effect(it)) }
                    }

                    is JobEvent.LiveClosed -> {
                        emit(TurnEvent.SocketClosed(event.code))
                        if (!turn.ended) {
                            turn = turn.copy(streaming = false, ended = true, message = turn.message.copy(isProcessing = false))
                            emit(TurnEvent.Updated(turn))
                        }
                    }

                    is JobEvent.Terminal -> {
                        emit(TurnEvent.ThreadReloaded(event.data))
                        done = true
                    }

                    is JobEvent.Snapshot -> {
                        Unit
                    }

                    is JobEvent.Failed -> {
                        emit(TurnEvent.Effect(TurnEffect.Failed("Could not load messages for thread $threadId.")))
                        done = true
                    }

                    JobEvent.Overdue -> {
                        done = true
                    }

                    is JobEvent.LiveConnected, is JobEvent.Stumble -> {
                        Unit
                    }
                }
            }
    }
}

/** Rule 13: close the socket (the collector cancels), revoke the task, fall back to the briefing cancel. */
class CancelTurnUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(taskId: String?, backendThreadId: String?): EmptyResult<DataError> {
        val conversationId = backendThreadId ?: return Result.Success(Unit)
        return repository.cancelTask(taskId, conversationId)
    }
}

class SkipBriefingUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(backendThreadId: String?): EmptyResult<DataError> {
        val conversationId = backendThreadId ?: return Result.Success(Unit)
        return repository.cancelBriefing(conversationId)
    }
}

// ── Use cases: briefing ─────────────────────────────────────────────────────

class BriefSelectedCasesUseCase(private val repository: BriefRepository) {
    suspend operator fun invoke(request: BriefRequest): EmptyResult<BriefError> {
        if (request.threadId.isBlank()) return Result.Failure(BriefError.ConversationInitializing)
        val count = when (request) {
            is BriefRequest.Selection -> request.selectedCasesJson.size
            is BriefRequest.AutoConfirm -> request.caseNames.size
            is BriefRequest.Source -> 1
        }
        if (count == 0 || (count > BriefSelectionRules.MAX_SELECTION && request !is BriefRequest.Source)) {
            return Result.Failure(BriefError.Rejected("Select between 1 and ${BriefSelectionRules.MAX_SELECTION} cases."))
        }
        return repository.briefSelected(request)
    }
}

/** Rule 15: a source card click tries the cached brief first; a miss runs the brief pipeline on a dedicated socket. */
sealed interface SourceCardOutcome {
    data class CachedBrief(val brief: CaseBrief) : SourceCardOutcome

    /** The brief request went out; the result arrives as `extracted_case_result` on [frames]. */
    data class Briefing(val frames: Flow<CaseBrief>) : SourceCardOutcome

    data class Failed(val error: BriefError) : SourceCardOutcome
}

class OpenSourceCardUseCase(
    private val briefs: BriefRepository,
    private val sockets: SocketClient,
    private val apiBaseUrl: String,
) {
    suspend operator fun invoke(source: CaseSourceCard, backendThreadId: String?, assistantMessageId: String?): SourceCardOutcome {
        val cached = briefs.lookup(citation = source.citation, caseId = source.id, title = source.title)
        (cached as? Result.Success)?.data?.let { return SourceCardOutcome.CachedBrief(it) }
        val threadId = backendThreadId?.takeIf { it.isNotBlank() } ?: return SourceCardOutcome.Failed(BriefError.ConversationInitializing)
        val frames = sockets.connect(SocketUrls.chat(apiBaseUrl), listOf(SocketSubscription.chatJoinFrame(threadId)), ReconnectPolicy(maxAttempts = 0))
        val sent = briefs.briefSelected(
            BriefRequest.Source(
                threadId,
                assistantMessageId,
                source.rawJson ?: CitationChipRules.sourceJson(source.citation.ifEmpty { source.title }),
            ),
        )
        if (sent is Result.Failure) return SourceCardOutcome.Failed(sent.error)
        return SourceCardOutcome.Briefing(
            frames.transformWhile { event ->
                when (event) {
                    is SocketEvent.Frame -> {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(event.text) as? kotlinx.serialization.json.JsonObject
                        }.getOrNull()
                        val type = (obj?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        when (type) {
                            "extracted_case_result", "tool.case_brief_result" -> (
                                obj?.get(
                                    "data",
                                ) as? kotlinx.serialization.json.JsonObject
                            )?.let(ChatStreamReducer::caseBrief)?.let {
                                emit(it)
                            }

                            else -> Unit
                        }
                        type != "briefing_batch_complete" && type != "error" && type != "crawl_error" && type != "credits.insufficient"
                    }

                    is SocketEvent.Closed -> {
                        false
                    }

                    is SocketEvent.Connected -> {
                        true
                    }
                }
            },
        )
    }
}

/** The citation chip (rule 16) is a source card whose fields all equal the citation string. */
class BriefCitationUseCase(private val openSourceCard: OpenSourceCardUseCase) {
    suspend operator fun invoke(citation: String, backendThreadId: String?, assistantMessageId: String?): SourceCardOutcome =
        openSourceCard(
            CaseSourceCard(id = citation, title = citation, citation = citation, rawJson = CitationChipRules.sourceJson(citation)),
            backendThreadId,
            assistantMessageId,
        )
}

class LoadBriefHistoryUseCase(private val repository: BriefRepository) {
    suspend operator fun invoke(caseId: String): Result<CaseBriefHistory, DataError> = repository.history(caseId)
}

/** Row r16 + socket §4: ask, then listen on a fresh socket joined with `register_thread`. */
class FollowUpUseCase(
    private val repository: BriefRepository,
    private val sockets: SocketClient,
    private val apiBaseUrl: String,
) {
    operator fun invoke(caseId: String, query: String): Flow<FollowUpEvent> = flow {
        val threadId = when (val asked = repository.followUp(caseId, query.trim())) {
            is Result.Failure -> {
                emit(FollowUpEvent.Failed((asked.error as? BriefError.Rejected)?.message ?: "The follow-up could not be sent."))
                return@flow
            }

            is Result.Success -> {
                asked.data
            }
        }
        emit(FollowUpEvent.Progress(FollowUpReducer.THINKING_PLACEHOLDER))
        sockets.connect(SocketUrls.chat(apiBaseUrl), listOf(FollowUpReducer.joinFrame(threadId)), ReconnectPolicy(maxAttempts = 0))
            .transformWhile { event ->
                when (event) {
                    is SocketEvent.Frame -> {
                        val parsed = FollowUpReducer.parse(event.text)
                        if (parsed != null) emit(parsed)
                        parsed !is FollowUpEvent.Final && parsed !is FollowUpEvent.Failed
                    }

                    is SocketEvent.Closed -> {
                        false
                    }

                    is SocketEvent.Connected -> {
                        true
                    }
                }
            }
            .collect { emit(it) }
    }
}

/** Rows r17/r18: start, then poll every 3 s until the file (200) or a failure. */
class GenerateBriefFileUseCase(
    private val repository: BriefRepository,
    private val polling: PollingEngine,
    private val schedule: PollSchedule = PollSchedule(ChatTimers.BRIEF_FILE_POLL_MILLIS, ChatTimers.BRIEF_FILE_POLL_MILLIS, 10 * 60_000L, 1.0),
) {
    operator fun invoke(caseId: String, format: BriefFileFormat): Flow<BriefFileStatus> = flow {
        val taskId = when (val started = repository.generateFile(caseId, format)) {
            is Result.Failure -> {
                emit(BriefFileStatus.Failed((started.error as? BriefError.Rejected)?.message))
                return@flow
            }

            is Result.Success -> {
                started.data
            }
        }
        emit(BriefFileStatus.Generating)
        polling.poll(schedule, isTerminal = { it !is BriefFileStatus.Generating }) { repository.fileStatus(taskId, caseId, format) }
            .collect { event ->
                when (event) {
                    is PollEvent.Terminal -> emit(event.data)
                    is PollEvent.Snapshot -> Unit
                    is PollEvent.Stumble -> emit(BriefFileStatus.Failed(null))
                    is PollEvent.Failed -> emit(BriefFileStatus.Failed(null))
                    PollEvent.Overdue -> emit(BriefFileStatus.Failed("The document took too long to generate."))
                }
            }
    }
        .transformWhile { status ->
            emit(status)
            status is BriefFileStatus.Generating
        }
}

// ── Use cases: statutes, feedback, export ───────────────────────────────────

class ExplainStatuteUseCase(private val repository: StatuteRepository) {
    suspend operator fun invoke(source: StatuteSourceCard, question: String? = null): Result<String, StatuteError> =
        repository.explain(source.rawJson ?: """{"title":"${source.title}","citation":"${source.citation}"}""", question?.trim()?.takeIf { it.isNotEmpty() })
}

class SendAnswerFeedbackUseCase(private val repository: FeedbackRepository) {
    suspend operator fun invoke(request: FeedbackRequest): EmptyResult<DataError> = repository.send(request)
}

/** Rows r24–r26: start, poll 5 s × 30, then fetch the file from the host root without a bearer. */
class ExportAnswerToWordUseCase(
    private val repository: ExportRepository,
    private val polling: PollingEngine,
    private val brand: String,
    private val pollMillis: Long = ChatTimers.EXPORT_POLL_MILLIS,
    private val maxAttempts: Int = ChatTimers.EXPORT_MAX_ATTEMPTS,
) {
    suspend operator fun invoke(rawText: String, conversationId: String?, localeDate: String, isoDate: String): Result<ExportedDocument, ExportError> {
        val conversation = conversationId?.takeIf { it.isNotBlank() } ?: return Result.Failure(ExportError.NoActiveConversation)
        val taskId = when (val started = repository.formatToWord(rawText, conversation, ExportNamingRules.documentName(brand, localeDate))) {
            is Result.Failure -> return started
            is Result.Success -> started.data
        }
        var outcome: Result<ExportedDocument, ExportError>? = null
        val schedule = PollSchedule(initialMillis = pollMillis, maxMillis = pollMillis, capMillis = pollMillis * (maxAttempts - 1), backoffFactor = 1.0)
        polling.poll(schedule, isTerminal = { it !is ExportStatus.Pending }) { repository.status(taskId) }.collect { event ->
            when (event) {
                is PollEvent.Terminal -> outcome = when (val status = event.data) {
                    is ExportStatus.Success -> repository.download(status.fileUrl, ExportNamingRules.fileName(brand, isoDate)).let { d ->
                        when (d) {
                            is Result.Success -> d
                            is Result.Failure -> Result.Failure(ExportError.Transport(d.error))
                        }
                    }

                    is ExportStatus.Failure -> Result.Failure(ExportError.Failed(status.error))

                    ExportStatus.Pending -> Result.Failure(ExportError.TimedOut)
                }

                is PollEvent.Failed -> outcome = Result.Failure(ExportError.Transport(event.error))

                PollEvent.Overdue -> outcome = Result.Failure(ExportError.TimedOut)

                is PollEvent.Snapshot, is PollEvent.Stumble -> Unit
            }
        }
        return outcome ?: Result.Failure(ExportError.TimedOut)
    }
}

/** Row r28/r29: a Pro artifact goes through the signed-URL route; a legacy draft is a direct media download. */
class OpenDraftDocumentUseCase(private val repository: ExportRepository, private val mediaBaseUrl: String) {
    suspend operator fun invoke(card: DraftDocumentCard): Result<ArtifactDownload, DataError> {
        val url = card.fileUrl?.takeIf { it.isNotBlank() } ?: return Result.Failure(DataError.Local.NOT_FOUND)
        return if (card.needsSignedUrl) {
            repository.signedUrl(url)
        } else {
            val absolute = DraftDocumentRules.absoluteMediaUrl(mediaBaseUrl, url) ?: url
            repository.download(absolute, card.documentName).map { ArtifactDownload.Bytes(it) }
        }
    }
}

// ── Use cases: drafting registry, playbooks, bundles ────────────────────────

class LoadDraftingRegistryUseCase(private val repository: DraftingRepository) {
    suspend operator fun invoke(): DraftingRegistry = repository.registry()
}

class LoadPlaybookUseCase(private val repository: DraftingRepository) {
    suspend operator fun invoke(documentType: String): Result<Playbook, PlaybookError> = repository.playbook(documentType)
}

class SavePlaybookUseCase(private val repository: DraftingRepository) {
    suspend operator fun invoke(documentType: String, content: String, expectedVersion: Long?): Result<Playbook, PlaybookError> =
        repository.savePlaybook(documentType, content, expectedVersion)
}

class ResetPlaybookUseCase(private val repository: DraftingRepository) {
    suspend operator fun invoke(documentType: String): Result<Playbook, PlaybookError> = repository.resetPlaybook(documentType)
}

class ListPlaybookVersionsUseCase(private val repository: DraftingRepository) {
    suspend operator fun invoke(documentType: String): Result<List<PlaybookVersion>, PlaybookError> = repository.versions(documentType)
}

class LoadPlaybookVersionUseCase(private val repository: DraftingRepository) {
    suspend operator fun invoke(
        documentType: String,
        versionId: String,
    ): Result<PlaybookVersionDetail, PlaybookError> = repository.version(documentType, versionId)
}

class RestorePlaybookVersionUseCase(private val repository: DraftingRepository) {
    suspend operator fun invoke(documentType: String, versionId: String): Result<Playbook, PlaybookError> = repository.restore(documentType, versionId)
}

/** Rule 6: a Playbooks-browser skill applied to the composer. */
class LoadPlaybookSkillUseCase(private val drafting: DraftingRepository) {
    suspend operator fun invoke(settings: ComposerSettings, skill: PlaybookSkill): ComposerSettings =
        PlaybookSkillRules.load(settings, drafting.registry(), skill)
}

/** Rule 19: confirming a bundle offer is a normal chat turn carrying the synthesised prompt. */
class ConfirmDocumentGenerationUseCase(private val sendTurn: SendTurnUseCase) {
    operator fun invoke(offer: DocumentBundleOffer, selected: List<String>, threadId: String?, settings: ComposerSettings): Flow<TurnEvent> =
        sendTurn(SendRequest(prompt = GenerationConfirmRules.confirmationPrompt(offer, selected), threadId = threadId, settings = settings))
}

class AddDocumentsToBundleUseCase(private val repository: BundleRepository) {
    suspend operator fun invoke(offer: BundleAdditionOffer): Result<BundleAdditionOutcome, DataError> = repository.addDocuments(offer.optionId, offer.documents)
}

/** Rule 21: the generation card's status from the tracked task, when one is known. */
class GenerationCardStatusUseCase {
    operator fun invoke(card: DocumentGenerationCard, trackedStatus: String?): String = GenerationCardRules.status(trackedStatus ?: card.status)
}

/** Keeps the flow operators above readable: `JobEvent` → mapped turn events for a thread reload. */
internal fun Flow<List<ChatMessage>>.asReloads(): Flow<TurnEvent> = map { TurnEvent.ThreadReloaded(it) }
