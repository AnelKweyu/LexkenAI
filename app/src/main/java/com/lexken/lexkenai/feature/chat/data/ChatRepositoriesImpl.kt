package com.lexken.lexkenai.feature.chat.data

import com.lexken.lexkenai.core.auth.HttpFailure
import com.lexken.lexkenai.core.auth.httpCall
import com.lexken.lexkenai.core.network.safeCall
import com.lexken.lexkenai.core.network.toNetworkError
import com.lexken.lexkenai.core.network.toPart
import com.lexken.lexkenai.core.network.toTextPart
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.map
import com.lexken.lexkenai.core.result.mapError
import com.lexken.lexkenai.feature.billing.domain.CreditsWallRules
import com.lexken.lexkenai.feature.chat.domain.ArtifactDownload
import com.lexken.lexkenai.feature.chat.domain.BriefError
import com.lexken.lexkenai.feature.chat.domain.BriefFileFormat
import com.lexken.lexkenai.feature.chat.domain.BriefFileStatus
import com.lexken.lexkenai.feature.chat.domain.BriefRepository
import com.lexken.lexkenai.feature.chat.domain.BriefRequest
import com.lexken.lexkenai.feature.chat.domain.BundleAdditionOutcome
import com.lexken.lexkenai.feature.chat.domain.BundleRepository
import com.lexken.lexkenai.feature.chat.domain.CaseBrief
import com.lexken.lexkenai.feature.chat.domain.CaseBriefHistory
import com.lexken.lexkenai.feature.chat.domain.ChatMessage
import com.lexken.lexkenai.feature.chat.domain.ChatRepository
import com.lexken.lexkenai.feature.chat.domain.ChatStreamReducer
import com.lexken.lexkenai.feature.chat.domain.Conversation
import com.lexken.lexkenai.feature.chat.domain.ConversationDisplayRules
import com.lexken.lexkenai.feature.chat.domain.DocumentType
import com.lexken.lexkenai.feature.chat.domain.DraftingRegistry
import com.lexken.lexkenai.feature.chat.domain.DraftingRepository
import com.lexken.lexkenai.feature.chat.domain.ExportError
import com.lexken.lexkenai.feature.chat.domain.ExportNamingRules
import com.lexken.lexkenai.feature.chat.domain.ExportRepository
import com.lexken.lexkenai.feature.chat.domain.ExportStatus
import com.lexken.lexkenai.feature.chat.domain.ExportedDocument
import com.lexken.lexkenai.feature.chat.domain.FeedbackRepository
import com.lexken.lexkenai.feature.chat.domain.FeedbackRequest
import com.lexken.lexkenai.feature.chat.domain.MessageRole
import com.lexken.lexkenai.feature.chat.domain.Playbook
import com.lexken.lexkenai.feature.chat.domain.PlaybookError
import com.lexken.lexkenai.feature.chat.domain.PlaybookVersion
import com.lexken.lexkenai.feature.chat.domain.PlaybookVersionDetail
import com.lexken.lexkenai.feature.chat.domain.SendAccepted
import com.lexken.lexkenai.feature.chat.domain.SendError
import com.lexken.lexkenai.feature.chat.domain.SendRequest
import com.lexken.lexkenai.feature.chat.domain.SourceCardRules
import com.lexken.lexkenai.feature.chat.domain.StatuteError
import com.lexken.lexkenai.feature.chat.domain.StatuteRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RemoteChatRepository(private val api: ChatApi, private val json: Json) : ChatRepository {
    override suspend fun conversations(): Result<List<Conversation>, DataError> =
        safeCall { ConversationDisplayRules.sorted(api.conversations().map { it.toDomain() }) }

    override suspend fun messages(threadId: String): Result<List<ChatMessage>, DataError> =
        safeCall { api.messages(threadId).map { it.toDomain() } }

    override suspend fun rename(threadId: String, title: String): EmptyResult<DataError> =
        safeCall { api.patch(threadId, ConversationPatchBody(title)) }.map { }

    override suspend fun delete(threadId: String): EmptyResult<DataError> =
        safeCall { api.delete(threadId) }.map { }

    override suspend fun send(request: SendRequest): Result<SendAccepted, SendError> {
        val fields = request.formFields().mapValues { (_, v) -> v.toTextPart() }
        val files = request.attachments.map { it.toPart("files") }
        return when (val answered = httpCall(json) { api.send(fields, files) }) {
            is Result.Success -> {
                val response = answered.data
                val body = response.body()
                when {
                    response.isSuccessful && body?.threadId != null && (response.code() == 200 || response.code() == 202) -> {
                        Result.Success(SendAccepted(body.threadId, body.taskId, response.code()))
                    }

                    response.isSuccessful -> {
                        Result.Failure(
                            SendError.Rejected(
                                body?.error ?: (body?.detail as? JsonPrimitive)?.contentOrNull ?: "Unexpected response status: ${response.code()}",
                            ),
                        )
                    }

                    else -> {
                        Result.Failure(response.errorBody()?.string().toSendError(response.code()))
                    }
                }
            }

            is Result.Failure -> {
                Result.Failure(answered.error.toSendError())
            }
        }
    }

    override suspend fun cancelTask(taskId: String?, conversationId: String): EmptyResult<DataError> {
        val first = safeCall { api.cancelTask(CancelTaskBody(taskId, conversationId)) }
        if (first is Result.Success) return Result.Success(Unit)
        return safeCall { api.cancelBriefing(CancelBriefingBody(conversationId)) }.map { }
    }

    override suspend fun cancelBriefing(conversationId: String): EmptyResult<DataError> =
        safeCall { api.cancelBriefing(CancelBriefingBody(conversationId)) }.map { }

    private fun String?.toSendError(status: Int): SendError {
        val body = this?.takeIf { it.isNotBlank() } ?: return SendError.Transport(status.toNetwork())
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val code = obj?.get("error_code")?.jsonPrimitive?.contentOrNull ?: obj?.get("code")?.jsonPrimitive?.contentOrNull
        return when {
            code == "ai_session_required" || code == "turnstile_required" -> {
                SendError.SessionChallenge
            }

            code == "INSUFFICIENT_AI_CREDITS" || code == "INSUFFICIENT_AI_CREDITS_VIEW" -> {
                SendError.InsufficientCredits(CreditsWallRules.fromHttpBody(body) ?: com.lexken.lexkenai.feature.billing.domain.CreditsWall(null, 0, null))
            }

            code == "NO_VALID_SUBSCRIPTION" || code == "SUBSCRIPTION_REQUIRED" -> {
                SendError.NoSubscription(CreditsWallRules.fromHttpBody(body))
            }

            code == "AI_ACCESS_DENIED" || code == "AI_ACCESS_DENIED_VIEW" || code == "AI_PLAN_RESTRICTION" -> {
                SendError.PlanRestricted
            }

            else -> {
                val sentence =
                    obj?.get("error")?.jsonPrimitive?.contentOrNull ?: obj?.get("detail")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
                sentence?.let { SendError.Rejected(it) } ?: SendError.Transport(status.toNetwork())
            }
        }
    }

    private fun HttpFailure.toSendError(): SendError = SendError.Transport(network)

    private fun Int.toNetwork(): DataError.Network = toNetworkError()
}

internal fun ConversationDto.toDomain(): Conversation = Conversation(
    id = id,
    rawTitle = title,
    title = ConversationDisplayRules.titleOf(title, id),
    kind = ConversationDisplayRules.kindOf(title),
    lastUpdated = lastUpdated,
    lastUpdatedMillis = lastUpdated?.let(::parseIsoMillis),
)

internal fun MessageDto.toDomain(): ChatMessage {
    val cards = metadata?.let { ChatStreamReducer.cardsFromMetadata(it) } ?: com.lexken.lexkenai.feature.chat.domain.AnswerCards()
    val briefs = associatedCases.mapNotNull(ChatStreamReducer::caseBrief)
        .ifEmpty { (metadata?.get("extractedCases") as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull(ChatStreamReducer::caseBrief).orEmpty() }
    return ChatMessage(
        id = id,
        role = if (role.equals("user", ignoreCase = true)) MessageRole.USER else MessageRole.ASSISTANT,
        content = content.orEmpty(),
        createdAt = createdAt,
        cards = cards,
        progressSteps = ragProgressSteps.ifEmpty {
            (metadata?.get("ragProgressSteps") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        },
        extractedCases = briefs,
        attachmentNames = ChatStreamReducer.userFiles(metadata),
        draftingRunId = (metadata?.get("run_id") as? JsonPrimitive)?.contentOrNull,
        isSubmissionAnalysis = (metadata?.get("type") as? JsonPrimitive)?.contentOrNull == "submission_analysis",
    )
}

/** ISO-8601 with an offset, as the server writes it; `java.time` is out of reach at minSdk 24. */
internal fun parseIsoMillis(value: String): Long? {
    val text = value.trim().replace("Z", "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd")
    for (pattern in patterns) {
        val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)?.time }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

class RemoteBriefRepository(private val api: BriefApi, private val json: Json) : BriefRepository {
    override suspend fun briefSelected(request: BriefRequest): EmptyResult<BriefError> {
        val body = buildJsonObject {
            put("thread_id", JsonPrimitive(request.threadId))
            when (request) {
                is BriefRequest.Selection -> {
                    put("user_query", JsonPrimitive(request.userQuery))
                    put("selected_cases", JsonArray(request.selectedCasesJson.map { parseOrString(it) }))
                }

                is BriefRequest.AutoConfirm -> {
                    request.assistantMessageId?.let { put("assistant_message_id", JsonPrimitive(it)) }
                    put("selected_cases", JsonArray(request.caseNames.map { JsonPrimitive(it) }))
                    put("case_search_new_mode", JsonPrimitive(true))
                    put("old_case_search_old_mode", JsonPrimitive(request.oldCaseSearch))
                }

                is BriefRequest.Source -> {
                    request.assistantMessageId?.let { put("assistant_message_id", JsonPrimitive(it)) }
                    put("selected_cases", JsonArray(listOf(parseOrString(request.sourceJson))))
                    put("case_search_new_mode", JsonPrimitive(true))
                    put("old_case_search_old_mode", JsonPrimitive(false))
                    put("source_mode", JsonPrimitive("2rah_v2_full_case"))
                }
            }
        }
        return httpCall(json) { api.briefSelected(body) }.map { }.mapError { failure ->
            failure.body?.error?.let { BriefError.Rejected(it) } ?: failure.body?.firstSentence?.let { BriefError.Rejected(it) }
                ?: BriefError.Transport(failure.network)
        }
    }

    override suspend fun lookup(citation: String?, caseId: String?, title: String?): Result<CaseBrief?, DataError> = safeCall {
        val response = api.lookup(citation.orEmpty(), caseId.orEmpty(), title.orEmpty())
        val body = response.body()
        val summary = (body?.get("summary") as? JsonPrimitive)?.contentOrNull
        if (SourceCardRules.isLookupHit(response.code(), summary)) body?.let(ChatStreamReducer::caseBrief) else null
    }

    override suspend fun history(caseId: String): Result<CaseBriefHistory, DataError> =
        when (val fetched = safeCall { api.history(caseId) }) {
            is Result.Success -> Result.Success(
                CaseBriefHistory(
                    threadId = fetched.data.threadId,
                    messages = fetched.data.messages.mapIndexed { index, m ->
                        ChatMessage(
                            id = m.id ?: "history-$index",
                            role = if (m.role.equals("user", ignoreCase = true)) MessageRole.USER else MessageRole.ASSISTANT,
                            content = m.content.orEmpty(),
                            createdAt = m.createdAt,
                        )
                    },
                ),
            )

            is Result.Failure -> if (fetched.error == DataError.Network.NOT_FOUND) Result.Success(CaseBriefHistory(null, emptyList())) else fetched
        }

    override suspend fun followUp(caseId: String, query: String): Result<String, BriefError> =
        when (val asked = httpCall(json) { api.followUp(caseId, FollowUpBody(query)) }) {
            is Result.Success -> asked.data.threadId?.takeIf { it.isNotBlank() }?.let { Result.Success(it) }
                ?: Result.Failure(BriefError.Rejected("The follow-up was not accepted."))

            is Result.Failure -> Result.Failure(asked.error.body?.error?.let { BriefError.Rejected(it) } ?: BriefError.Transport(asked.error.network))
        }

    override suspend fun generateFile(caseId: String, format: BriefFileFormat): Result<String, BriefError> =
        when (val started = httpCall(json) { api.generateFile(format.wire, caseId) }) {
            is Result.Success -> started.data.taskId?.takeIf { it.isNotBlank() }?.let { Result.Success(it) }
                ?: Result.Failure(BriefError.Rejected("The document could not be started."))

            is Result.Failure -> Result.Failure(started.error.body?.error?.let { BriefError.Rejected(it) } ?: BriefError.Transport(started.error.network))
        }

    override suspend fun fileStatus(taskId: String, caseId: String, format: BriefFileFormat): Result<BriefFileStatus, DataError> = safeCall {
        val response = api.fileStatus(taskId)
        when (response.code()) {
            202 -> {
                BriefFileStatus.Generating
            }

            200 -> {
                val bytes = response.body()?.bytes() ?: ByteArray(0)
                BriefFileStatus.Ready(bytes, ExportNamingRules.briefFileName(response.headers()["Content-Disposition"], caseId, format))
            }

            else -> {
                val text = response.errorBody()?.string()
                val error = text?.let { runCatching { (json.parseToJsonElement(it) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull }.getOrNull() }
                BriefFileStatus.Failed(error)
            }
        }
    }

    private fun parseOrString(text: String): JsonElement = runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
}

class RemoteStatuteRepository(private val api: StatuteApi, private val json: Json) : StatuteRepository {
    override suspend fun explain(sourceJson: String, question: String?): Result<String, StatuteError> {
        val body = buildJsonObject {
            put("source", runCatching { json.parseToJsonElement(sourceJson) }.getOrElse { JsonPrimitive(sourceJson) })
            question?.let { put("question", JsonPrimitive(it)) }
        }
        return when (val answered = httpCall(json) { api.explain(body) }) {
            is Result.Success -> answered.data.answer?.let { Result.Success(it) } ?: Result.Failure(StatuteError.Rejected("No answer was produced."))

            is Result.Failure -> Result.Failure(
                answered.error.body?.detail?.let { StatuteError.Rejected(it) } ?: answered.error.body?.firstSentence?.let { StatuteError.Rejected(it) }
                    ?: StatuteError.Transport(answered.error.network),
            )
        }
    }
}

class RemoteFeedbackRepository(private val api: FeedbackApi) : FeedbackRepository {
    override suspend fun send(request: FeedbackRequest): EmptyResult<DataError> = safeCall {
        api.send(FeedbackBody(request.conversationId, request.responseId, request.type.wire, request.prompt, request.answer, request.details))
    }.map { }
}

/**
 * The export and draft downloads. [plainClient] carries no bearer: media files live on the host
 * root (`API_BASE_URL_DOWNLOAD`), outside `/api/`.
 */
class RemoteExportRepository(
    private val api: ExportApi,
    private val plainClient: OkHttpClient,
    private val json: Json,
) : ExportRepository {
    override suspend fun formatToWord(rawText: String, conversationId: String, documentName: String): Result<String, ExportError> =
        when (val started = httpCall(json) { api.formatToWord(FormatBody(rawText, conversationId, documentName)) }) {
            is Result.Success -> started.data.taskId?.takeIf { it.isNotBlank() }?.let { Result.Success(it) }
                ?: Result.Failure(ExportError.Failed("No task was started."))

            is Result.Failure -> Result.Failure(started.error.body?.error?.let { ExportError.Failed(it) } ?: ExportError.Transport(started.error.network))
        }

    override suspend fun status(taskId: String): Result<ExportStatus, DataError> = safeCall {
        val dto = api.status(taskId)
        val result = dto.result
        when {
            result?.fileUrl?.isNotBlank() == true && result.status != "FAILURE" -> ExportStatus.Success(result.fileUrl)
            result?.status == "FAILURE" || dto.status == "FAILURE" -> ExportStatus.Failure(result?.error)
            dto.status == "SUCCESS" && result != null -> ExportStatus.Failure(result.error ?: "No file was produced.")
            else -> ExportStatus.Pending
        }
    }

    override suspend fun download(fileUrl: String, filename: String): Result<ExportedDocument, DataError> = safeCall {
        val response = plainClient.newCall(Request.Builder().url(fileUrl).build()).execute()
        response.use {
            if (!it.isSuccessful) throw retrofit2.HttpException(retrofit2.Response.error<Unit>(it.code, it.body))
            ExportedDocument(it.body.bytes(), filename)
        }
    }

    override suspend fun signedUrl(downloadUrl: String): Result<ArtifactDownload, DataError> = safeCall {
        // `/api/drafting/artifacts/<id>/download/` relative to the `/api/` base → the doubled prefix nginx needs.
        val response = api.artifact(downloadUrl.removePrefix("/"))
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        val body = response.body() ?: error("empty artifact answer")
        val contentType = body.contentType()?.toString().orEmpty()
        if (contentType.contains("json")) {
            val dto = json.decodeFromString(SignedUrlDto.serializer(), body.string())
            ArtifactDownload.Url(dto.url ?: error("no url"), dto.filename)
        } else {
            val filename = ExportNamingRules.briefFileName(response.headers()["Content-Disposition"], "artifact", BriefFileFormat.DOCX)
            ArtifactDownload.Bytes(ExportedDocument(body.bytes(), filename))
        }
    }
}

/** Rule r38: the registry is fetched once per process; any failure falls back to the bundled ten types. */
class RemoteDraftingRepository(private val api: DraftingApi, private val json: Json) : DraftingRepository {
    private val mutex = Mutex()
    private var cached: DraftingRegistry? = null

    override suspend fun registry(): DraftingRegistry = mutex.withLock {
        cached?.let { return it }
        val loaded = when (val fetched = safeCall { api.documentTypes() }) {
            is Result.Success -> fetched.data.toDomain()
            is Result.Failure -> FALLBACK
        }
        if (!loaded.fromFallback) cached = loaded
        loaded
    }

    override suspend fun playbook(documentType: String) = httpCall(json) { api.playbook(documentType) }.map { it.toDomain() }.mapError { it.toPlaybookError() }

    override suspend fun savePlaybook(documentType: String, content: String, expectedVersion: Long?) =
        httpCall(json) { api.savePlaybook(documentType, PlaybookSaveBody(content, expectedVersion)) }.map { it.toDomain() }.mapError { it.toPlaybookError() }

    override suspend fun resetPlaybook(documentType: String) = httpCall(json) {
        api.resetPlaybook(documentType)
    }.map { it.toDomain() }.mapError { it.toPlaybookError() }

    override suspend fun versions(documentType: String): Result<List<PlaybookVersion>, PlaybookError> =
        httpCall(json) { api.versions(documentType) }.map { dto ->
            dto.versions.map { v ->
                PlaybookVersion(v.id, v.version, v.changeType, v.editedBy.editorName(), v.createdAt, v.stats?.additions, v.stats?.deletions)
            }
        }.mapError { it.toPlaybookError() }

    override suspend fun version(documentType: String, versionId: String): Result<PlaybookVersionDetail, PlaybookError> =
        httpCall(json) { api.version(documentType, versionId) }.map { v ->
            PlaybookVersionDetail(v.version, v.changeType, v.editedBy.editorName(), v.createdAt, v.diff, v.content)
        }.mapError { it.toPlaybookError() }

    override suspend fun restore(documentType: String, versionId: String) =
        httpCall(json) { api.restore(documentType, versionId) }.map { it.toDomain() }.mapError { it.toPlaybookError() }

    private fun HttpFailure.toPlaybookError(): PlaybookError = when {
        network == DataError.Network.CONFLICT -> PlaybookError.Conflict
        body?.detail != null -> PlaybookError.Rejected(body.detail)
        else -> PlaybookError.Transport(network)
    }

    private fun JsonElement?.editorName(): String? = when (this) {
        is JsonObject -> (this["name"] as? JsonPrimitive)?.contentOrNull ?: (this["email"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> contentOrNull
        else -> null
    }

    companion object {
        /** `draftingTypes.js:12-23` — the ten bundled types the web falls back to. */
        val FALLBACK = DraftingRegistry(
            documentTypes = listOf(
                "land_sale_agreement" to "Land sale agreement",
                "letter_of_offer" to "Letter of offer",
                "written_submissions" to "Written submissions",
                "lease_agreement" to "Lease agreement",
                "non_disclosure_agreement" to "Non-disclosure agreement",
                "loan_agreement" to "Loan agreement",
                "joint_venture_agreement" to "Joint venture agreement",
                "services_agreement" to "Services agreement",
                "employment_contract" to "Employment contract",
                "influencer_agreement" to "Influencer agreement",
            ).map { (key, label) -> DocumentType(key = key, playbook = key, label = label, structured = false) },
            outputFormats = listOf("text", "docx", "both"),
            defaultOutputFormat = "docx",
            canEditPlaybooks = false,
            houseStyleAvailable = false,
            fromFallback = true,
        )
    }
}

internal fun DocumentTypesDto.toDomain() = DraftingRegistry(
    documentTypes = documentTypes.map { DocumentType(it.key, it.playbook, it.label ?: it.key, it.structured ?: false) },
    outputFormats = outputFormats.mapNotNull { f -> (f as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull ?: (f as? JsonPrimitive)?.contentOrNull },
    defaultOutputFormat = defaultOutputFormat ?: "docx",
    canEditPlaybooks = canEditPlaybooks ?: false,
    houseStyleAvailable = houseStyleAvailable ?: false,
)

internal fun PlaybookDto.toDomain() = Playbook(
    content = content.orEmpty(),
    version = version,
    canEdit = canEdit ?: false,
    isCustomized = isCustomized ?: false,
    title = title,
)

class RemoteBundleRepository(private val api: BundleApi) : BundleRepository {
    override suspend fun addDocuments(optionId: String, documents: List<String>): Result<BundleAdditionOutcome, DataError> = safeCall {
        val dto = api.addDocuments(AddDocumentsBody(optionId, documents))
        BundleAdditionOutcome(dto.bundleName, dto.added, dto.alreadyPresent)
    }
}

/** The metadata object of a message, when a caller needs the raw form. */
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject ?: (this as? JsonElement)?.let { runCatching { it.jsonObject }.getOrNull() }
