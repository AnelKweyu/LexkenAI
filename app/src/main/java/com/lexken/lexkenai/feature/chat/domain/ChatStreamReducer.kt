package com.lexken.lexkenai.feature.chat.domain

import com.lexken.lexkenai.feature.billing.domain.CreditsWall
import com.lexken.lexkenai.feature.billing.domain.CreditsWallRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** What the page does after a frame, beyond updating the turn (the web's side effects). */
sealed interface TurnEffect {
    /** `submission.complete`: re-fetch the thread from REST for the analyzer's persisted message (rule 27). */
    data object ReloadThread : TurnEffect

    /** The turn is over: close the socket (the server does so itself after `stream.end`). */
    data object CloseSocket : TurnEffect

    data class ShowCreditsWall(val wall: CreditsWall) : TurnEffect

    data class ShowSubscriptionWall(val wall: CreditsWall) : TurnEffect

    /** `apiError` + Retry queued. */
    data class Failed(val message: String) : TurnEffect

    /** A DMS tool handoff to auto-open once per task (rule 23). */
    data class AutoOpenHandoff(val card: ToolHandoffCard) : TurnEffect

    /** `tool.briefing_confirm`: start the countdown card (rule 14). */
    data class StartBriefingCountdown(val card: BriefingConfirmCard) : TurnEffect
}

/**
 * The assistant turn being assembled from the chat socket (`LawrisAI.jsx:1137-1899`): the
 * message row, the streamed text, the cards, and whether the turn is still open. [effects]
 * are the side effects of the *last* frame only — the use case drains them each step.
 */
data class ChatTurn(
    val flow: TurnFlow,
    val brand: String,
    val message: ChatMessage,
    val accumulated: String = "",
    val streaming: Boolean = false,
    val ended: Boolean = false,
    /** RAG: a selection card or auto-briefing is open, so `stream.end` does not end the turn. */
    val briefingPending: Boolean = false,
    /** Whole extra assistant rows (`tool.full_message_result`), deduped by id. */
    val extraMessages: List<ChatMessage> = emptyList(),
    /** `tool.append_case_brief` targets other messages: id → briefs. */
    val appendedBriefs: Map<String, List<CaseBrief>> = emptyMap(),
    val autoOpenedTasks: Set<String> = emptySet(),
    val effects: List<TurnEffect> = emptyList(),
)

/** The reducer over every inbound `type` of the chat socket (appendix chat §1), both branches. */
object ChatStreamReducer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun start(flow: TurnFlow, brand: String, placeholder: ChatMessage): ChatTurn = ChatTurn(flow, brand, placeholder)

    @Suppress("CyclomaticComplexMethod") // one branch per inbound event type, as the web's switch
    fun reduce(turn: ChatTurn, frame: String): ChatTurn {
        val obj = runCatching { json.parseToJsonElement(frame) as? JsonObject }.getOrNull() ?: return turn.copy(effects = emptyList())
        val t = turn.copy(effects = emptyList())
        val type = obj.str("type")
        val data = obj["data"] as? JsonObject
        // `send.chunk` frames arrive untyped: `{"message": chunk}`.
        if (type == null) return obj.str("message")?.let { chunk(t, it) } ?: t
        return when (type) {
            "stream.start" -> streamStart(t, data)

            "stream.chunk", "stream.token" -> obj.str("message")?.let { chunk(t, it) } ?: t

            "progress_update", "status", "status.update", "drafting.stage", "drafting.progress" -> progress(t, type, obj, data)

            "drafting.file" -> data?.let { draftingFile(t, it) } ?: t

            "grounding.sources" -> data?.let { grounding(t, it) } ?: t

            "rag.case_sources" -> data?.let { caseSources(t, it) } ?: t

            "rag.statute_sources" -> data?.let { statuteSources(t, it) } ?: t

            "rag_analysis_complete" -> data?.let { ragAnalysisComplete(t, it) } ?: t

            "extracted_case_result", "tool.case_brief_result" -> data?.let { extractedCase(t, it) } ?: t

            "extracted_case_not_found" -> t.withStep(obj.str("message") ?: "No brief could be produced for that case.")

            "briefing_batch_complete" -> briefingComplete(t)

            "tool.submission_analysis_started", "tool.contract_analysis_started", "tool.dms_tool_started" -> data?.let { handoff(t, type, it) } ?: t

            "submission.status", "submission.verifying_case" -> submissionProgress(t, obj)

            "submission.complete" -> t.copy(
                streaming = false,
                ended = true,
                message = t.message.copy(isProcessing = false),
                effects = listOf(TurnEffect.ReloadThread, TurnEffect.CloseSocket),
            )

            "submission.error" -> failed(t, obj.str("message") ?: "The submission analysis failed.", append = false)

            "tool.document_bundle_offer" -> data?.let { bundleOffer(t, it) } ?: t

            "tool.document_bundle_started" -> data?.let { bundleStarted(t, it) } ?: t

            "tool.document_bundle_addition_offer" -> data?.let { bundleAddition(t, it) } ?: t

            "tool.briefing_confirm" -> data?.let { briefingConfirm(t, it) } ?: t

            "tool.full_message_result" -> data?.let { fullMessage(t, it) } ?: t

            "tool.append_case_brief" -> data?.let { appendBrief(t, it) } ?: t

            "draft.document.start" -> draftStart(t, data, obj)

            "draft.document.ready" -> data?.let { draftReady(t, it) } ?: t

            "draft.document.error" -> draftError(t, data, obj)

            "full_llm_response" -> fullResponse(t, obj)

            "stream.end" -> streamEnd(t)

            "error", "rag.error", "stream.error", "crawl_error" -> failed(
                t,
                obj.str("error") ?: obj.str("message") ?: "The AI service reported an error.",
                append = true,
            )

            "credits.insufficient" -> wall(t, obj, frame, subscription = false)

            "subscription.error" -> wall(t, obj, frame, subscription = true)

            else -> obj.str("message")?.let { t.withStep(it) } ?: t
        }
    }

    // ── handlers ──────────────────────────────────────────────────────────

    private fun streamStart(t: ChatTurn, data: JsonObject?): ChatTurn {
        data ?: return t
        val id = data.str("id") ?: return t
        val persisted = t.message.copy(
            id = id,
            content = data.str("content")?.takeIf { it.isNotBlank() } ?: t.message.content,
            createdAt = data.str("created_at") ?: t.message.createdAt,
            extractedCases = data["associated_cases_details"].objects().mapNotNull(::caseBrief).ifEmpty { t.message.extractedCases },
            progressSteps = (data["ragProgressSteps"] ?: (data["metadata"] as? JsonObject)?.get("ragProgressSteps")).strings().ifEmpty {
                t.message.progressSteps
            },
            cards = (data["metadata"] as? JsonObject)?.get("case_sources").objects().mapNotNull(::caseSource).takeIf { it.isNotEmpty() }
                ?.let { t.message.cards.copy(caseSources = it) } ?: t.message.cards,
        )
        return t.copy(message = persisted)
    }

    /** The General branch accumulates; a placeholder content resets the accumulator; RAG never streams chunks. */
    private fun chunk(t: ChatTurn, piece: String): ChatTurn {
        if (t.flow == TurnFlow.RAG) return t
        val base = if (OptimisticMessages.isPlaceholder(t.message.content) && t.accumulated.isEmpty()) "" else t.accumulated
        val accumulated = base + piece
        return t.copy(accumulated = accumulated, streaming = true, message = t.message.copy(content = accumulated))
    }

    private fun progress(t: ChatTurn, type: String, obj: JsonObject, data: JsonObject?): ChatTurn {
        val message = obj.str("message") ?: data?.str("message") ?: return t
        var turn = t
        if (t.flow == TurnFlow.GENERAL) {
            val decision = ProgressOrdering.decide(t.message.draftingRunId, t.message.lastSequence, obj.str("run_id"), obj.long("sequence"))
            if (!decision.accept) return t
            turn = t.copy(message = t.message.copy(draftingRunId = decision.runId, lastSequence = decision.lastSequence))
        }
        turn = turn.withStep(message)
        val stage = obj.str("stage") ?: data?.str("stage")
        if (type == "drafting.stage" && stage in setOf("drafting", "downloading", "storing")) {
            val name = data?.str("document_name") ?: turn.message.cards.draftDocument?.documentName ?: "Generated document"
            turn = turn.withCards { it.copy(draftDocument = DraftDocumentCard(documentName = name, status = "processing", messageId = turn.message.id)) }
        }
        return turn
    }

    private fun draftingFile(t: ChatTurn, data: JsonObject): ChatTurn {
        val artifact = artifact(data)
        return t.withCards { it.copy(draftingArtifact = artifact, draftDocument = DraftDocumentRules.fromArtifact(artifact).copy(messageId = t.message.id)) }
    }

    private fun grounding(t: ChatTurn, data: JsonObject): ChatTurn {
        val sources = webSources(data["sources"], data["grounding_supports"].objects())
        val queries = data["search_queries"].strings()
        val turn = t.withCards { it.copy(webSources = sources, searchQueries = queries.ifEmpty { it.searchQueries }) }
        return if (sources.isNotEmpty()) turn.withStep("Found ${sources.size} relevant source${if (sources.size == 1) "" else "s"} from web search") else turn
    }

    private fun caseSources(t: ChatTurn, data: JsonObject): ChatTurn =
        t.withCards { it.copy(caseSources = data["sources"].objects().mapNotNull(::caseSource)) }

    private fun statuteSources(t: ChatTurn, data: JsonObject): ChatTurn {
        val fromMemory = data["from_memory"].bool() ?: false
        return t.withCards { it.copy(statuteSources = data["sources"].objects().mapNotNull { statuteSource(it, fromMemory) }) }
    }

    private fun ragAnalysisComplete(t: ChatTurn, data: JsonObject): ChatTurn {
        val mentioned = data["mentioned_cases"].orEmptyArray().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { MentionedCase(it, element.toString()) }
                is JsonObject -> (element.str("name") ?: element.str("citation") ?: element.str("title"))?.let { MentionedCase(it, element.toString()) }
                else -> null
            }
        }
        val content = data.str("full_llm_response")?.takeIf { it.isNotBlank() } ?: t.message.content
        return t.copy(
            briefingPending = mentioned.isNotEmpty(),
            message = t.message.copy(content = content, cards = t.message.cards.copy(mentionedCases = mentioned)),
        )
    }

    private fun extractedCase(t: ChatTurn, data: JsonObject): ChatTurn {
        val brief = caseBrief(data) ?: return t
        return t.copy(message = t.message.copy(extractedCases = DedupeRules.appendBrief(t.message.extractedCases, brief)))
            .withStep("Briefed: ${brief.title.take(50)}")
    }

    private fun briefingComplete(t: ChatTurn): ChatTurn {
        val closed = t.flow == TurnFlow.GENERAL
        return t.copy(
            briefingPending = false,
            streaming = false,
            ended = t.ended || closed,
            message = t.message.copy(isProcessing = false, progressSteps = emptyList()),
            effects = if (closed) listOf(TurnEffect.CloseSocket) else emptyList(),
        )
    }

    private fun handoff(t: ChatTurn, type: String, data: JsonObject): ChatTurn {
        val card = handoffCard(data)
        val turn = when (type) {
            "tool.contract_analysis_started" -> t.withCards { it.copy(contractHandoff = card) }
            "tool.submission_analysis_started" -> t.withCards { it.copy(submissionHandoff = card) }
            else -> t.withCards { it.copy(dmsHandoff = card) }
        }
        if (DmsHandoffRules.shouldAutoOpen(card, turn.autoOpenedTasks)) {
            return turn.copy(autoOpenedTasks = turn.autoOpenedTasks + card.taskId!!, effects = listOf(TurnEffect.AutoOpenHandoff(card)))
        }
        return turn
    }

    private fun submissionProgress(t: ChatTurn, obj: JsonObject): ChatTurn {
        val line = obj.str("message") ?: obj.str("case_name")?.let { "Verifying $it" } ?: return t
        val card = t.message.cards.submissionHandoff?.copy(message = line, status = "running")
        return t.withStep(line).withCards { it.copy(submissionHandoff = card ?: it.submissionHandoff) }
    }

    private fun bundleOffer(t: ChatTurn, data: JsonObject): ChatTurn =
        bundleOffer(data)?.let { offer -> t.withCards { it.copy(documentBundleOffer = offer) } } ?: t

    private fun bundleStarted(t: ChatTurn, data: JsonObject): ChatTurn = t.withCards {
        it.copy(
            documentBundleOffer = null,
            documentGeneration = DocumentGenerationCard(
                optionId = data.str("option_id"),
                optionName = data.str("option_name") ?: "Document bundle",
                documents = data["documents"].strings(),
                runId = data.str("generation_run_id") ?: data.str("run_id"),
                taskId = data.str("task_id"),
                status = GenerationCardRules.status(data.str("run_status") ?: "running"),
            ),
        )
    }

    private fun bundleAddition(t: ChatTurn, data: JsonObject): ChatTurn =
        bundleAddition(data)?.let { offer -> t.withCards { it.copy(bundleAdditionOffer = offer) } } ?: t

    private fun briefingConfirm(t: ChatTurn, data: JsonObject): ChatTurn {
        val card = BriefingConfirmCard(
            cases = BriefSelectionRules.shown(
                data["cases"].orEmptyArray().mapNotNull { element ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull
                        is JsonObject -> element.str("name") ?: element.str("citation") ?: element.str("title")
                        else -> null
                    }
                },
            ),
            timeoutSeconds = BriefSelectionRules.timeout(data.int("timeout_seconds")),
            messageId = data.str("message_id"),
        )
        return t.copy(briefingPending = true, effects = listOf(TurnEffect.StartBriefingCountdown(card))).withCards { it.copy(briefingConfirm = card) }
    }

    private fun fullMessage(t: ChatTurn, data: JsonObject): ChatTurn {
        val id = data.str("id") ?: return t
        if (t.extraMessages.any { it.id == id } || t.message.id == id) return t
        val row = ChatMessage(
            id = id,
            role = if (data.str("role") == "user") MessageRole.USER else MessageRole.ASSISTANT,
            content = data.str("content").orEmpty(),
            createdAt = data.str("created_at"),
            extractedCases = data["associated_cases_details"].objects().mapNotNull(::caseBrief),
        )
        return t.copy(extraMessages = t.extraMessages + row)
    }

    private fun appendBrief(t: ChatTurn, data: JsonObject): ChatTurn {
        val target = data.str("target_message_id") ?: return t
        val brief = (data["case_brief"] as? JsonObject)?.let(::caseBrief) ?: return t
        if (target == t.message.id) return t.copy(message = t.message.copy(extractedCases = DedupeRules.appendBrief(t.message.extractedCases, brief)))
        val existing = t.appendedBriefs[target].orEmpty()
        return t.copy(appendedBriefs = t.appendedBriefs + (target to DedupeRules.appendBrief(existing, brief)))
    }

    private fun draftStart(t: ChatTurn, data: JsonObject?, obj: JsonObject): ChatTurn {
        val name = data?.str("document_name") ?: obj.str("document_name") ?: "Generated document"
        return t.withCards {
            it.copy(
                draftDocument = DraftDocumentCard(
                    documentName = name,
                    status = "processing",
                    messageId =
                        data?.str("message_id") ?: t.message.id,
                ),
            )
        }
    }

    /** Legacy `draft.document.ready` payloads are trusted as-is (rule 26). */
    private fun draftReady(t: ChatTurn, data: JsonObject): ChatTurn = t.withCards {
        it.copy(
            draftDocument = DraftDocumentCard(
                documentName = data.str("document_name") ?: it.draftDocument?.documentName ?: "Generated document",
                status = "ready",
                fileUrl = data.str("file_url"),
                needsSignedUrl = data["needs_signed_url"].bool() ?: false,
                templateId = data.str("template_id"),
                placeholderCount = data.int("placeholder_count") ?: 0,
                canReplace = data["can_replace"].bool() ?: false,
                messageId = data.str("message_id") ?: t.message.id,
            ),
        )
    }

    private fun draftError(t: ChatTurn, data: JsonObject?, obj: JsonObject): ChatTurn = t.withCards {
        it.copy(
            draftDocument = (it.draftDocument ?: DraftDocumentCard(documentName = data?.str("document_name") ?: "Generated document", status = "error"))
                .copy(status = "error", errorMessage = obj.str("message") ?: data?.str("message")),
        )
    }

    private fun fullResponse(t: ChatTurn, obj: JsonObject): ChatTurn {
        val content = obj.str("message") ?: t.message.content
        val metadata = obj["metadata"] as? JsonObject
        var cards = metadata?.let { cardsFromMetadata(it, t.message.cards) } ?: t.message.cards
        val file = obj["file"] as? JsonObject
        if (file != null) {
            val artifact = artifact(file)
            cards = cards.copy(draftingArtifact = artifact, draftDocument = DraftDocumentRules.fromArtifact(artifact).copy(messageId = t.message.id))
        } else if (metadata?.str("status") == "partial_success") {
            cards =
                cards.copy(
                    draftDocument = DraftDocumentCard(
                        documentName = cards.draftDocument?.documentName ?: "Generated document",
                        status = "error",
                        errorMessage = DraftDocumentRules.PARTIAL_SUCCESS_MESSAGE,
                    ),
                )
        }
        val briefs = metadata?.get("extractedCases").objects().mapNotNull(::caseBrief)
        return t.copy(
            accumulated = content,
            message = t.message.copy(
                content = content,
                cards = cards,
                extractedCases = briefs.fold(t.message.extractedCases) { acc, b -> DedupeRules.appendBrief(acc, b) },
            ),
        )
    }

    private fun streamEnd(t: ChatTurn): ChatTurn {
        if (t.flow == TurnFlow.RAG && t.briefingPending) return t
        return t.copy(
            streaming = false,
            ended = true,
            message = t.message.copy(isProcessing = false, progressSteps = emptyList()),
            effects = listOf(TurnEffect.CloseSocket),
        )
    }

    private fun failed(t: ChatTurn, message: String, append: Boolean): ChatTurn {
        val content = if (append) {
            listOf(
                t.message.content.takeIf {
                    !OptimisticMessages.isPlaceholder(it)
                }.orEmpty(),
                "**Error:** $message",
            ).filter { it.isNotBlank() }.joinToString("\n\n")
        } else {
            t.message.content
        }
        return t.copy(
            streaming = false,
            ended = true,
            message = t.message.copy(content = content, isProcessing = false),
            effects = listOf(TurnEffect.Failed(message), TurnEffect.CloseSocket),
        )
    }

    private fun wall(t: ChatTurn, obj: JsonObject, frame: String, subscription: Boolean): ChatTurn {
        val message = obj.str("message") ?: "Insufficient AI credits."
        val wall = CreditsWallRules.fromSocketFrame(frame) ?: CreditsWall(message, 0, null)
        val content = listOf(
            t.message.content.takeIf {
                !OptimisticMessages.isPlaceholder(it)
            }.orEmpty(),
            "**Error:** $message",
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        return t.copy(
            streaming = false,
            ended = true,
            message = t.message.copy(content = content, isProcessing = false),
            effects = listOf(
                if (subscription) TurnEffect.ShowSubscriptionWall(wall) else TurnEffect.ShowCreditsWall(wall),
                TurnEffect.Failed(message),
                TurnEffect.CloseSocket,
            ),
        )
    }

    // ── metadata → cards (shared with the thread loader) ──────────────────

    /** `full_llm_response.metadata` and the persisted `Message.metadata` carry the same keys. */
    fun cardsFromMetadata(metadata: JsonObject, base: AnswerCards = AnswerCards()): AnswerCards {
        val supports = metadata["grounding_supports"].objects()
        return base.copy(
            caseSources = metadata["case_sources"].objects().mapNotNull(::caseSource).ifEmpty { base.caseSources },
            statuteSources = metadata["statute_sources"].objects().mapNotNull { statuteSource(it, false) }.ifEmpty { base.statuteSources },
            webSources = webSources(metadata["grounding_sources"], supports).ifEmpty { base.webSources },
            searchQueries = metadata["search_queries"].strings().ifEmpty { base.searchQueries },
            contractHandoff = (metadata["contract_analysis_handoff"] as? JsonObject)?.let(::handoffCard) ?: base.contractHandoff,
            submissionHandoff = (metadata["submission_analysis_handoff"] as? JsonObject)?.let(::handoffCard) ?: base.submissionHandoff,
            dmsHandoff = (metadata["dms_tool_handoff"] as? JsonObject)?.let(::handoffCard) ?: base.dmsHandoff,
            documentBundleOffer = (metadata["document_bundle_offer"] as? JsonObject)?.let(::bundleOffer) ?: base.documentBundleOffer,
            bundleAdditionOffer = (metadata["bundle_addition_offer"] as? JsonObject)?.let(::bundleAddition) ?: base.bundleAdditionOffer,
            documentGeneration = (metadata["document_generation"] as? JsonObject)?.let(::generation) ?: base.documentGeneration,
            draftingArtifact = (metadata["drafting_artifact"] as? JsonObject)?.let(::artifact) ?: base.draftingArtifact,
            draftDocument = (metadata["draft_document"] as? JsonObject)?.let(::draftFromMetadata)
                ?: (metadata["drafting_artifact"] as? JsonObject)?.let { DraftDocumentRules.fromArtifact(artifact(it), it.str("status") ?: "ready") }
                ?: base.draftDocument,
            submissionAnalysis = if (metadata.str("type") == "submission_analysis") submission(metadata) else base.submissionAnalysis,
        )
    }

    fun caseSource(o: JsonObject): CaseSourceCard? {
        val title = o.str("title").clean() ?: o.str("citation").clean() ?: return null
        val citation = o.str("citation").clean().orEmpty()
        return CaseSourceCard(
            id = SourceCardRules.busyKey(o.str("case_id").clean(), o.str("id").clean(), citation) ?: title,
            title = title,
            citation = citation,
            court = o.str("court").clean(),
            judgmentDate = o.str("judgment_date").clean(),
            usageNote = o.str("usage_note").clean(),
            pageUrl = o.str("source_page_url").clean() ?: o.str("url").clean(),
            pdfUrl = o.str("pdf_url").clean() ?: o.str("spaces_url").clean(),
            rawJson = o.toString(),
        )
    }

    fun statuteSource(o: JsonObject, fromMemory: Boolean): StatuteSourceCard? {
        val title = o.str("title").clean() ?: "Statutory provision"
        val citation = o.str("citation").clean().orEmpty()
        val sections = o["primary_sections"].objects().mapNotNull { s ->
            val n = s.str("section_number").clean()
            val st = s.str("section_title").clean()
            when {
                n != null && st != null -> "s. $n — $st"
                n != null -> "s. $n"
                else -> st
            }
        }
        return StatuteSourceCard(
            id = o.str("statute_id").clean() ?: o.str("id").clean() ?: citation.ifEmpty { title },
            title = title,
            citation = citation,
            provisions = sections.ifEmpty { listOfNotNull(citation.takeIf { it.isNotEmpty() && it != title }) },
            snippet = o.str("snippet").clean(),
            pageUrl = o.str("url").clean() ?: o.str("source_page_url").clean(),
            pdfUrl = o.str("pdf_url").clean() ?: o.str("spaces_url").clean(),
            fromMemory = fromMemory,
            rawJson = o.toString(),
        )
    }

    fun webSources(element: JsonElement?, supports: List<JsonObject>): List<WebSource> {
        val items = element as? JsonArray ?: return emptyList()
        return items.mapIndexedNotNull { index, item ->
            val (uri, title) = when (item) {
                is JsonPrimitive -> item.contentOrNull to null
                is JsonObject -> (item.str("uri") ?: item.str("url")) to (item.str("title") ?: item.str("domain"))
                else -> null to null
            }
            val u = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val snippets = supports.filter { s ->
                (s["chunk_indices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.contains(index) == true
            }
                .mapNotNull { it.str("text")?.trim()?.takeIf { s -> s.isNotEmpty() } }
            WebSource(uri = u, title = title?.trim()?.takeIf { it.isNotEmpty() } ?: WebSource(u, u).domain, snippets = snippets)
        }
    }

    fun handoffCard(o: JsonObject): ToolHandoffCard = ToolHandoffCard(
        tool = HandoffTool.entries.firstOrNull { it.wire.isNotEmpty() && it.wire == o.str("tool")?.trim()?.lowercase() } ?: HandoffTool.UNKNOWN,
        status = o.str("status")?.trim()?.lowercase() ?: "queued",
        mode = o.str("mode").clean(),
        route = o.str("route").clean(),
        documentName = o.str("document_name").clean(),
        analysisId = o.str("analysis_id").clean(),
        sessionId = o.str("session_id").clean(),
        threadId = o.str("thread_id").clean(),
        taskId = o.str("task_id").clean(),
        message = o.str("message").clean(),
        progress = o.int("progress"),
        stage = o.str("stage").clean(),
        outputUrl = o.str("output_url").clean(),
    )

    fun bundleOffer(o: JsonObject): DocumentBundleOffer? {
        val docs = o["documents"].orEmptyArray().mapNotNull { d ->
            when (d) {
                is JsonObject -> d.str("name").clean()?.let { BundleDocument(it, d["required"].bool() ?: false) }
                is JsonPrimitive -> d.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { BundleDocument(it) }
                else -> null
            }
        }
        val suggested = o["suggested_documents"].strings()
        return DocumentBundleOffer(
            optionId = o.str("option_id").clean() ?: return null,
            optionName = o.str("option_name").clean() ?: "Document bundle",
            documents = docs.ifEmpty { suggested.map { BundleDocument(it) } },
            suggestedDocuments = suggested,
            defaultDocumentLimit = o.int("default_document_limit"),
            pending = (o.str("status") ?: "pending") == "pending",
        )
    }

    fun bundleAddition(o: JsonObject): BundleAdditionOffer? {
        val optionId = o.str("option_id").clean() ?: return null
        return BundleAdditionOffer(
            optionId = optionId,
            optionName = o.str("option_name").clean() ?: "the bundle",
            documents = o["documents"].strings(),
            currentBundle = o["current_bundle"].strings(),
            proposedBundle = o["proposed_bundle"].strings(),
            pending = (o.str("status") ?: "pending") == "pending",
        )
    }

    fun generation(o: JsonObject) = DocumentGenerationCard(
        optionId = o.str("option_id").clean(),
        optionName = o.str("option_name").clean() ?: "Document bundle",
        documents = o["documents"].strings().ifEmpty { o["selected_documents"].strings() },
        runId = o.str("generation_run_id").clean() ?: o.str("run_id").clean(),
        taskId = o.str("task_id").clean(),
        status = GenerationCardRules.status(o.str("status") ?: o.str("run_status")),
    )

    fun artifact(o: JsonObject) = DraftingArtifact(
        id = o.str("id").clean(),
        filename = o.str("filename").clean() ?: o.str("document_name").clean(),
        downloadUrl = o.str("download_url").clean() ?: o.str("file_url").clean(),
        sizeBytes = o.long("size_bytes"),
        templateId = o.str("template_id").clean(),
        templateStatus = o.str("template_status").clean(),
        placeholderCount = o.int("placeholder_count") ?: 0,
        canReplace = o["can_replace"].bool() ?: false,
        formSchemaUrl = o.str("form_schema_url").clean(),
    )

    private fun draftFromMetadata(o: JsonObject) = DraftDocumentCard(
        documentName = o.str("document_name").clean() ?: "Generated document",
        status = o.str("status")?.trim()?.lowercase() ?: "ready",
        fileUrl = o.str("file_url").clean(),
        needsSignedUrl = o["needs_signed_url"].bool() ?: false,
        templateId = o.str("template_id").clean(),
        placeholderCount = o.int("placeholder_count") ?: 0,
        canReplace = o["can_replace"].bool() ?: false,
        messageId = o.str("message_id").clean(),
    )

    private fun submission(metadata: JsonObject): SubmissionAnalysisCard {
        val result = metadata["result"] as? JsonObject ?: JsonObject(emptyMap())
        val extracted = result["extracted_cases"].objects()
        val verified = result["verified_cases"].objects()
        return SubmissionAnalysisCard(
            documentName = metadata.str("document_name").clean(),
            casesFound = extracted.size,
            verified = verified.size,
            unverified = (extracted.size - verified.size).coerceAtLeast(0),
            alternatives = result["suggestions"].objects().size,
            verifiedCases = verified.mapNotNull { it.str("case_name").clean() ?: it.str("title").clean() ?: it.str("citation").clean() },
            zipUrl = result.str("zip_url") ?: result.str("bundle_url"),
            listPdfUrl = result.str("list_pdf_url"),
            listDocxUrl = result.str("list_docx_url"),
        )
    }

    fun caseBrief(o: JsonObject): CaseBrief? {
        val id = o.str("case_id").clean() ?: o.str("id").clean() ?: o.str("citation").clean() ?: return null
        return CaseBrief(
            id = id,
            title = o.str("title").clean() ?: o.str("name").clean() ?: o.str("citation").clean() ?: id,
            citation = o.str("citation").clean(),
            summary = o.str("summary").clean(),
            brief = o.str("brief").clean(),
            url = o.str("url").clean() ?: o.str("case_url").clean() ?: (o["raw_data"] as? JsonObject)?.str("case_url").clean(),
            pdfUrl = o.str("pdf_url").clean() ?: (o["raw_data"] as? JsonObject)?.str("pdf_url").clean(),
            court = o.str("court").clean() ?: (o["metadata"] as? JsonObject)?.str("court").clean(),
            fromCache = o["from_cache"].bool() ?: false,
            rawJson = o.toString(),
        )
    }

    /** `metadata.files` on a user turn: bare names or `{name | filename | file_name}` objects. */
    fun userFiles(metadata: JsonObject?): List<String> = (metadata?.get("files") as? JsonArray)?.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> if (item.isString) item.content.clean() else null
            is JsonObject -> (item.str("name") ?: item.str("filename") ?: item.str("file_name") ?: item.str("original_name")).clean()
            else -> null
        }
    }.orEmpty()

    // ── helpers ───────────────────────────────────────────────────────────

    private fun ChatTurn.withStep(line: String): ChatTurn = copy(message = message.copy(progressSteps = ThinkingSentinel.append(message.progressSteps, line)))

    private fun ChatTurn.withCards(transform: (AnswerCards) -> AnswerCards): ChatTurn = copy(message = message.copy(cards = transform(message.cards)))

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != "null" }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let {
        it.booleanOrNull
            ?: it.contentOrNull?.lowercase()?.let { s ->
                if (s == "true") {
                    true
                } else if (s == "false") {
                    false
                } else {
                    null
                }
            }
    }
    private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    private fun JsonElement?.orEmptyArray(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
    private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s ->
            s.isNotEmpty()
        }
    }.orEmpty()
    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "None" && it != "null" }
}

// ── Case-search socket (appendix §2) and the brief follow-up socket (§4) ────

sealed interface CaseSearchEvent {
    data class Progress(val message: String) : CaseSearchEvent

    data class IndividualResult(val brief: CaseBrief) : CaseSearchEvent

    data class Results(val briefs: List<CaseBrief>) : CaseSearchEvent

    data class DatabaseUnavailable(val message: String?, val originalKeywords: String?, val alternatives: List<SearchAlternative>) : CaseSearchEvent

    data class Failed(val message: String) : CaseSearchEvent

    data object Registered : CaseSearchEvent

    data object Ignored : CaseSearchEvent
}

data class SearchAlternative(
    val id: String?,
    val title: String?,
    val description: String?,
    val action: String?,
    val mode: ChatMode?,
    val prefillPrompt: String?,
)

object CaseSearchReducer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun joinFrame(taskId: String): String = """{"type":"register_task","task_id":"$taskId"}"""

    fun parse(frame: String): CaseSearchEvent {
        val obj = runCatching { json.parseToJsonElement(frame) as? JsonObject }.getOrNull() ?: return CaseSearchEvent.Ignored
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull
        return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "status.registered" -> {
                CaseSearchEvent.Registered
            }

            "progress.update", "crawler.update" -> {
                message?.let { CaseSearchEvent.Progress(it) } ?: CaseSearchEvent.Ignored
            }

            "crawler.individual_result" -> {
                (obj["data"] as? JsonObject)?.let(ChatStreamReducer::caseBrief)?.let { CaseSearchEvent.IndividualResult(it) }
                    ?: CaseSearchEvent.Ignored
            }

            "crawler.result" -> {
                CaseSearchEvent.Results((obj["data"] as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull(ChatStreamReducer::caseBrief).orEmpty())
            }

            "crawler.database_unavailable" -> {
                CaseSearchEvent.DatabaseUnavailable(
                    message = message,
                    originalKeywords = (obj["original_keywords"] as? JsonPrimitive)?.contentOrNull,
                    alternatives = (obj["alternatives"] as? JsonArray)?.filterIsInstance<JsonObject>()?.map { a ->
                        SearchAlternative(
                            id = a.s("id"),
                            title = a.s("title"),
                            description = a.s("description"),
                            action = a.s("action"),
                            mode = when (a.s("mode")) {
                                "useInternalDb" -> ChatMode.INTERNAL_DB
                                "useGeneralAIChat" -> ChatMode.GENERAL_AI
                                else -> null
                            },
                            prefillPrompt = a.s("prefill_prompt"),
                        )
                    }.orEmpty(),
                )
            }

            "crawler.error", "error.polling", "crawler.terminated", "error.invalid_message", "error.invalid_json", "error.processing" -> {
                CaseSearchEvent.Failed(message ?: "The case search failed.")
            }

            else -> {
                message?.let { CaseSearchEvent.Progress(it) } ?: CaseSearchEvent.Ignored
            }
        }
    }

    /** Results dedupe by `case_id` (rule 34). */
    fun merge(results: List<CaseBrief>, brief: CaseBrief): List<CaseBrief> = DedupeRules.appendBrief(results, brief)

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

object FollowUpReducer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    const val THINKING_PLACEHOLDER = "Thinking…"

    /** The modal's join frame differs from the chat's: `register_thread` + `thread_id` (the consumer reads only `thread_id`). */
    fun joinFrame(threadId: String): String = """{"type":"register_thread","thread_id":"$threadId"}"""

    fun parse(frame: String): FollowUpEvent? {
        val obj = runCatching { json.parseToJsonElement(frame) as? JsonObject }.getOrNull() ?: return null
        val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
        return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "rag_progress", "rag_stream_start", "rag_stream_chunk" -> FollowUpEvent.Progress(content.orEmpty())
            "rag_final_response", "rag_stream_end" -> FollowUpEvent.Final(content.orEmpty(), (obj["message_id"] as? JsonPrimitive)?.contentOrNull)
            "rag_error", "error" -> FollowUpEvent.Failed(content ?: (obj["error"] as? JsonPrimitive)?.contentOrNull ?: "The follow-up failed.")
            else -> null
        }
    }
}
