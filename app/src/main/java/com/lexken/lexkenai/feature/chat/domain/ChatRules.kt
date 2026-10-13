package com.lexken.lexkenai.feature.chat.domain

import com.lexken.lexkenai.core.model.FileInput
import java.util.Calendar
import java.util.TimeZone

/** Rule 1: send only with a non-empty trimmed prompt, the UI not busy, and no request in flight. */
object ComposerRules {
    fun canSend(prompt: String, uiBusy: Boolean, requestInFlight: Boolean): Boolean =
        prompt.trim().isNotEmpty() && !uiBusy && !requestInFlight

    /** `uiDisabled` (LawrisAI.jsx:237). */
    fun uiBusy(loading: Boolean, streaming: Boolean, loadingMessages: Boolean, briefingCases: Boolean, caseSearchShowingProgress: Boolean): Boolean =
        loading || streaming || loadingMessages || briefingCases || caseSearchShowingProgress
}

/** Rule 2: attachments are gated on the file extension, never on MIME, size or count. */
object AttachmentRules {
    val EXTENSIONS: Set<String> = setOf(
        "pdf",
        "doc",
        "docx",
        "odt",
        "rtf",
        "txt",
        "md",
        "csv",
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "heic",
        "heif",
        "mp3",
        "wav",
        "m4a",
        "ogg",
        "webm",
        "mp4",
        "mpeg",
        "mpga",
    )
    val CONVERTED_TO_PDF: Set<String> = setOf("doc", "docx", "odt", "rtf")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isAllowed(file: FileInput): Boolean = extensionOf(file.name) in EXTENSIONS

    /** The first rejected name, or null when all pass (the web shows the first error for 3 s). */
    fun firstRejected(files: List<FileInput>): String? = files.firstOrNull { !isAllowed(it) }?.name

    fun isConvertedToPdf(file: FileInput): Boolean = extensionOf(file.name) in CONVERTED_TO_PDF
}

/** Rule 3: five exclusive modes; turning the active one off falls back to General AI. */
object ModeRules {
    fun toggle(settings: ComposerSettings, mode: ChatMode, on: Boolean): ComposerSettings = when {
        on -> settings.copy(mode = mode)
        settings.mode == mode -> settings.copy(mode = ChatMode.GENERAL_AI)
        else -> settings
    }
}

/** Rule 4: `researchPresets.js` — preset ↔ (engine, effort); unknown pairs display as `fast`. */
object ResearchPresetRules {
    data class Pair(val engine: DraftingEngine, val effort: DraftingEffort)

    val PRESETS: Map<ResearchPreset, Pair> = mapOf(
        ResearchPreset.INSTANT to Pair(DraftingEngine.V2, DraftingEffort.OFF),
        ResearchPreset.FAST to Pair(DraftingEngine.V2, DraftingEffort.MEDIUM),
        ResearchPreset.PRO to Pair(DraftingEngine.V3, DraftingEffort.VERY_HIGH),
    )

    fun apply(settings: ComposerSettings, preset: ResearchPreset): ComposerSettings {
        val pair = PRESETS.getValue(preset)
        return settings.copy(draftingEngine = pair.engine, draftingEffort = pair.effort)
    }

    fun presetOf(settings: ComposerSettings): ResearchPreset =
        PRESETS.entries.firstOrNull { it.value.engine == settings.draftingEngine && it.value.effort == settings.draftingEffort }?.key ?: ResearchPreset.FAST

    /** The Precedents engine dropdown: `fast` = v2, `pro` = v3 (default v3). */
    fun precedentEngine(preset: ResearchPreset): PrecedentEngine = if (preset == ResearchPreset.PRO) PrecedentEngine.V3 else PrecedentEngine.V2
}

/** Rule 5: the document-type picker and the playbook button appear only on Draft Assistant + `very_high`. */
object ProDrafterRules {
    fun isProDrafter(settings: ComposerSettings): Boolean =
        settings.mode == ChatMode.DRAFTING_ASSISTANT && settings.draftingEffort == DraftingEffort.VERY_HIGH

    /** The playbook button needs a type that has a `playbook` key in the registry. */
    fun hasPlaybook(registry: DraftingRegistry, documentTypeKey: String): Boolean =
        registry.documentTypes.any { it.key == documentTypeKey && !it.playbook.isNullOrBlank() }
}

/** Rule 6: a playbook skill turns Draft Assistant on, forces `pro`, and resolves the document type. */
object PlaybookSkillRules {
    private fun norm(value: String?): String? = value?.trim()?.lowercase()?.replace('-', '_')?.takeIf { it.isNotEmpty() }

    /** `documentTypeForSkill`: backend key if in the registry → slug match on key/playbook → name ≡ label → unchanged. */
    fun documentTypeForSkill(registry: DraftingRegistry, skill: PlaybookSkill, current: String): String {
        val types = registry.documentTypes
        skill.draftingDocumentType?.let { key -> types.firstOrNull { it.key == key }?.let { return it.key } }
        norm(skill.slug)?.let { slug -> types.firstOrNull { norm(it.key) == slug || norm(it.playbook) == slug }?.let { return it.key } }
        types.firstOrNull { it.label.trim().equals(skill.name.trim(), ignoreCase = true) }?.let { return it.key }
        return current
    }

    fun load(settings: ComposerSettings, registry: DraftingRegistry, skill: PlaybookSkill): ComposerSettings =
        ResearchPresetRules.apply(settings.copy(mode = ChatMode.DRAFTING_ASSISTANT), ResearchPreset.PRO)
            .let { it.copy(draftingDocumentType = documentTypeForSkill(registry, skill, it.draftingDocumentType)) }
}

/** Rule 7: a welcome quick action activates its mode and may set the `fast` preset. */
object QuickActionRules {
    fun apply(settings: ComposerSettings, mode: ChatMode, usesFastPreset: Boolean): ComposerSettings {
        val activated = if (settings.mode == mode) settings else settings.copy(mode = mode)
        return if (usesFastPreset) ResearchPresetRules.apply(activated, ResearchPreset.FAST) else activated
    }
}

/** Rule 8: the thread ids the page keeps and when they change. */
data class ThreadState(
    val activeThreadId: String? = null,
    /** From the send answer — what briefing and cancel use. */
    val backendThreadId: String? = null,
    val currentTaskId: String? = null,
    val settings: ComposerSettings = ComposerSettings(),
)

object ThreadLifecycleRules {
    /** New chat: everything back to the defaults (General AI, web sources on, precedent engine v3). */
    fun newChat(): ThreadState = ThreadState()

    /** Selecting a thread is ignored while loading or streaming, or when it is already active. */
    fun canSelect(state: ThreadState, threadId: String, loading: Boolean, streaming: Boolean): Boolean =
        !loading && !streaming && state.activeThreadId != threadId

    /** Selecting resets the modes to General AI and nulls `backendThreadId`. */
    fun select(state: ThreadState, threadId: String): ThreadState =
        state.copy(activeThreadId = threadId, backendThreadId = null, currentTaskId = null, settings = state.settings.copy(mode = ChatMode.GENERAL_AI))

    /** The send answered: adopt the returned thread (a mismatch switches to it) and remember the backend id and task. */
    fun adopt(state: ThreadState, accepted: SendAccepted): ThreadState =
        state.copy(
            activeThreadId = accepted.threadId?.takeIf { it.isNotBlank() } ?: state.activeThreadId,
            backendThreadId = accepted.threadId?.takeIf { it.isNotBlank() } ?: state.backendThreadId,
            currentTaskId = accepted.taskId ?: state.currentTaskId,
        )

    /** The thread id the send carries: the active one, or the backend one for case search; `""` when new. */
    fun threadIdForSend(state: ThreadState): String = when (state.settings.mode) {
        ChatMode.CASE_SEARCH, ChatMode.OLD_CASE_SEARCH -> state.backendThreadId?.takeIf { state.activeThreadId != null }.orEmpty()
        else -> state.activeThreadId.orEmpty()
    }
}

/** Rule 9: the optimistic rows and their placeholders. */
object OptimisticMessages {
    const val SESSION_PLACEHOLDER = "Acquiring and authenticating your AI session…"
    const val PROCESSING_PLACEHOLDER = "Processing query..."
    const val DRAFTING_PLACEHOLDER = "Drafting response..."
    const val RAG_FIRST_STEP = "Initiating analysis..."

    fun userId(nowMillis: Long) = "user-$nowMillis"

    fun pendingAssistantId(nowMillis: Long) = "assistant-session-$nowMillis"

    fun acceptedAssistantId(nowMillis: Long) = "assistant-$nowMillis"

    fun errorId(nowMillis: Long) = "err-$nowMillis"

    fun isTempAssistant(id: String): Boolean = id.startsWith("assistant-")

    fun isError(id: String): Boolean = id.startsWith("err-")

    fun userMessage(nowMillis: Long, prompt: String, attachmentNames: List<String>) =
        ChatMessage(id = userId(nowMillis), role = MessageRole.USER, content = prompt, attachmentNames = attachmentNames)

    fun pendingAssistant(nowMillis: Long) =
        ChatMessage(
            id = pendingAssistantId(nowMillis),
            role = MessageRole.ASSISTANT,
            content = "",
            isProcessing = true,
            progressSteps = listOf(SESSION_PLACEHOLDER),
        )

    /** After the 202: the placeholder row the stream will fill (RAG and General differ in their first step). */
    fun acceptedAssistant(nowMillis: Long, flow: TurnFlow, brand: String) = ChatMessage(
        id = acceptedAssistantId(nowMillis),
        role = MessageRole.ASSISTANT,
        content = PROCESSING_PLACEHOLDER,
        isProcessing = true,
        progressSteps = listOf(if (flow == TurnFlow.RAG) RAG_FIRST_STEP else "$brand is thinking..."),
    )

    /** The rows a send adds: both on a first try, only the assistant row on a retry. */
    fun rowsForSend(nowMillis: Long, prompt: String, attachmentNames: List<String>, isRetry: Boolean): List<ChatMessage> =
        if (isRetry) listOf(pendingAssistant(nowMillis)) else listOf(userMessage(nowMillis, prompt, attachmentNames), pendingAssistant(nowMillis))

    fun isPlaceholder(content: String): Boolean = content == PROCESSING_PLACEHOLDER || content == DRAFTING_PLACEHOLDER
}

/** Rule 10: a "… is thinking…" line is dropped once a real progress message arrives. */
object ThinkingSentinel {
    private val PATTERN = Regex("\\bis thinking[.…]*$", RegexOption.IGNORE_CASE)

    fun isSentinel(line: String): Boolean = PATTERN.containsMatchIn(line.trim())

    /** Append [line] (deduplicated) and drop every sentinel when [line] is a real message. */
    fun append(steps: List<String>, line: String): List<String> {
        val kept = if (isSentinel(line)) steps else steps.filterNot(::isSentinel)
        return if (line in kept) kept else kept + line
    }
}

/** Rule 11: General-branch progress frames are ordered by `run_id` + `sequence`. */
object ProgressOrdering {
    data class Decision(val accept: Boolean, val runId: String?, val lastSequence: Long?)

    fun decide(currentRunId: String?, lastSequence: Long?, frameRunId: String?, frameSequence: Long?): Decision {
        if (frameRunId != null && currentRunId != null && frameRunId != currentRunId) return Decision(false, currentRunId, lastSequence)
        val runId = currentRunId ?: frameRunId
        if (frameSequence != null && lastSequence != null && frameSequence <= lastSequence) return Decision(false, runId, lastSequence)
        return Decision(true, runId, frameSequence ?: lastSequence)
    }
}

/** Rule 12: the last failed request, replayed by Retry after the trailing `err-*` row is removed. */
sealed interface LastRequest {
    data class PromptSubmit(val request: SendRequest) : LastRequest

    data class LoadMessages(val threadId: String) : LastRequest
}

object RetryRules {
    fun messagesBeforeRetry(messages: List<ChatMessage>): List<ChatMessage> =
        if (messages.lastOrNull()?.let { it.role == MessageRole.ASSISTANT && OptimisticMessages.isError(it.id) } == true) messages.dropLast(1) else messages
}

/** Rule 13: Stop rewrites the last assistant row only while it is still a placeholder. */
object CancelRules {
    const val CANCELLED_TEXT = "Request cancelled by user."

    fun afterCancel(messages: List<ChatMessage>): List<ChatMessage> {
        val last = messages.lastOrNull() ?: return messages
        if (last.role != MessageRole.ASSISTANT) return messages
        val content = if (OptimisticMessages.isPlaceholder(last.content)) CANCELLED_TEXT else last.content
        return messages.dropLast(1) + last.copy(content = content, isProcessing = false, progressSteps = emptyList())
    }
}

/** Rule 14: at most two cases per briefing; the auto-brief card shows five and pre-selects two. */
object BriefSelectionRules {
    const val MAX_SELECTION = 2
    const val AUTO_BRIEF_SHOWN = 5
    const val DEFAULT_TIMEOUT_SECONDS = 300

    fun toggle(selected: List<String>, name: String): List<String> = when {
        name in selected -> selected - name
        selected.size >= MAX_SELECTION -> selected
        else -> selected + name
    }

    fun shown(cases: List<String>): List<String> = cases.take(AUTO_BRIEF_SHOWN)

    fun preselected(cases: List<String>): List<String> = cases.take(MAX_SELECTION)

    fun canConfirm(selected: List<String>, backendThreadId: String?): Boolean = selected.isNotEmpty() && !backendThreadId.isNullOrBlank()

    fun timeout(timeoutSeconds: Int?): Int = timeoutSeconds?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS
}

/** Rule 15: a source card is busy when the briefing key matches `case_id || id || citation`. */
object SourceCardRules {
    fun busyKey(caseId: String?, id: String?, citation: String?): String? =
        caseId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: citation?.takeIf { it.isNotBlank() }

    /** A lookup hit needs a 200 with `summary` present; anything else falls through to the brief pipeline. */
    fun isLookupHit(status: Int, summary: String?): Boolean = status == 200 && !summary.isNullOrBlank()
}

/** Rule 16: the citation chip regex over paragraph and list text. */
object CitationChipRules {
    val PATTERN = Regex("([A-Z][^\\[\\]\\n]{2,90}?\\sv\\.?\\s[^\\[\\]\\n]{2,90}?\\[\\d{4}\\]\\s*e?KLR)")

    fun citations(text: String): List<String> = PATTERN.findAll(text).map { it.groupValues[1].trim() }.toList()

    /** The chip's brief request: `{citation, name, case_id}` all equal to the citation string. */
    fun sourceJson(citation: String): String {
        val escaped = citation.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"citation":"$escaped","name":"$escaped","case_id":"$escaped"}"""
    }
}

/** Rule 18: title prefixes → kind badges, stripped for display; sidebar day buckets; search filter. */
object ConversationDisplayRules {
    private val PREFIXES: List<Pair<Regex, ConversationKind>> = ConversationKind.entries.map { kind ->
        Regex("^" + Regex.escape(kind.prefix) + "\\s*", RegexOption.IGNORE_CASE) to kind
    }

    fun kindOf(rawTitle: String?): ConversationKind? = rawTitle?.let { t -> PREFIXES.firstOrNull { it.first.containsMatchIn(t) }?.second }

    fun titleOf(rawTitle: String?, id: String): String {
        if (rawTitle.isNullOrBlank()) return "Conversation $id"
        val match = PREFIXES.firstOrNull { it.first.containsMatchIn(rawTitle) }
        return (match?.let { rawTitle.replace(it.first, "") } ?: rawTitle).trim().ifEmpty { "Conversation $id" }
    }

    /** Client-side sort: `last_updated` descending (row r4). */
    fun sorted(conversations: List<Conversation>): List<Conversation> = conversations.sortedByDescending { it.lastUpdatedMillis ?: 0L }

    /** `Sidebar.jsx:17-53`: calendar-day buckets in [zone]. */
    fun groupOf(updatedAtMillis: Long, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): ConversationGroup {
        val today = dayNumber(nowMillis, zone)
        val day = dayNumber(updatedAtMillis, zone)
        return when {
            day >= today -> ConversationGroup.TODAY
            day == today - 1 -> ConversationGroup.YESTERDAY
            day >= today - 7 -> ConversationGroup.PREVIOUS_7_DAYS
            day >= today - 30 -> ConversationGroup.PREVIOUS_30_DAYS
            else -> ConversationGroup.OLDER
        }
    }

    fun sections(
        conversations: List<Conversation>,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Pair<ConversationGroup, List<Conversation>>> {
        val grouped = sorted(conversations).groupBy { groupOf(it.lastUpdatedMillis ?: 0L, nowMillis, zone) }
        return ConversationGroup.entries.mapNotNull { group -> grouped[group]?.let { group to it } }
    }

    /** The search modal filters on a title substring or an exact id. */
    fun matches(conversation: Conversation, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return conversation.title.contains(q, ignoreCase = true) || conversation.id == q
    }

    /** Days since the epoch in [zone] — `java.time` is out of reach at minSdk 24 without desugaring. */
    private fun dayNumber(millis: Long, zone: TimeZone): Long = Math.floorDiv(millis + zone.getOffset(millis), 86_400_000L)
}

/** Rule 19: the generation confirm card's selection and the prompt it sends back through the chat. */
object GenerationConfirmRules {
    fun defaultSelection(offer: DocumentBundleOffer): List<String> {
        val names = offer.documents.map { it.name }
        val suggested = offer.suggestedDocuments.filter { it in names }
        val base = when {
            suggested.isNotEmpty() -> suggested
            offer.documents.any { it.required } -> offer.documents.filter { it.required }.map { it.name }
            else -> names
        }
        return cap(base, offer.defaultDocumentLimit)
    }

    /** `default_document_limit` caps the selection; 0 means none; null means uncapped. */
    fun cap(selection: List<String>, limit: Int?): List<String> = if (limit == null) selection else selection.take(limit)

    fun toggled(offer: DocumentBundleOffer, selected: List<String>, name: String): List<String> {
        if (offer.documents.any { it.name == name && it.required }) return selected
        if (name in selected) return selected - name
        val limit = offer.defaultDocumentLimit
        return if (limit != null && selected.size >= limit) selected else selected + name
    }

    fun confirmationPrompt(offer: DocumentBundleOffer, selected: List<String>): String {
        val chosen = offer.documents.map { it.name }.filter { it in selected }
        val bundle = offer.optionName.takeIf { it.isNotBlank() }?.let { " for the $it bundle" }.orEmpty()
        return "Yes — please generate these documents$bundle: ${chosen.joinToString(", ")}."
    }

    /** Rendered only on the latest assistant message, while pending and before a generation exists. */
    fun shouldRender(isLatestAssistant: Boolean, offer: DocumentBundleOffer?, generation: DocumentGenerationCard?): Boolean =
        isLatestAssistant && offer != null && offer.pending && generation == null
}

/** Rule 20: the bundle-addition card shows `proposed_bundle` when present, else current + documents. */
object BundleAdditionRules {
    fun shownBundle(offer: BundleAdditionOffer): List<String> =
        offer.proposedBundle.ifEmpty { offer.currentBundle + offer.documents }

    fun shouldRender(isLatestAssistant: Boolean, offer: BundleAdditionOffer?): Boolean = isLatestAssistant && offer?.pending == true
}

/** Rule 21: the generation card's status from the background task. */
object GenerationCardRules {
    fun status(wire: String?): String = when (wire?.trim()?.lowercase()) {
        null, "" -> "unknown"
        "running", "queued", "in_progress", "processing", "pending", "started", "progress", "cancellation_requested" -> "running"
        "complete", "completed", "success" -> "complete"
        "failed", "error", "failure", "partial_failure", "cancelled", "revoked" -> "failed"
        else -> "running"
    }
}

/** Rule 23: DMS tool handoffs auto-open once per task, two tools share a window, progress patches. */
object DmsHandoffRules {
    fun windowFor(tool: HandoffTool): HandoffTool = if (tool == HandoffTool.TENTH_LINING_WITH_PAGINATION) HandoffTool.TENTH_LINING else tool

    /** True the first time a given task id is seen; [opened] is the set of task ids already auto-opened. */
    fun shouldAutoOpen(card: ToolHandoffCard, opened: Set<String>): Boolean =
        card.tool.isDmsTool && !card.taskId.isNullOrBlank() && card.taskId !in opened

    /** `patchFromDmsProgress`: a stage of `complete` finishes it, `error` or FAILURE fails it, else it is started. */
    fun patch(card: ToolHandoffCard, stage: String?, status: String?, message: String?, progress: Int?, outputUrl: String?): ToolHandoffCard = when {
        stage == "complete" || status == "SUCCESS" -> card.copy(
            status = "complete",
            progress = 100,
            outputUrl = outputUrl ?: card.outputUrl,
            stage =
                stage ?: card.stage,
            message = message ?: card.message,
        )

        stage == "error" || status == "FAILURE" -> card.copy(status = "error", stage = stage ?: card.stage, message = message ?: card.message)

        else -> card.copy(status = "started", stage = stage ?: card.stage, message = message ?: card.message, progress = progress ?: card.progress)
    }
}

/** Rule 24: what "open" does on a handoff card. */
sealed interface HandoffOpen {
    data class DmsTool(val tool: HandoffTool, val taskId: String?) : HandoffOpen

    data class Analyzer(val sessionId: String?, val preset: String?) : HandoffOpen

    data class Route(val route: String) : HandoffOpen

    data object Nothing : HandoffOpen
}

object HandoffOpenRules {
    fun open(card: ToolHandoffCard): HandoffOpen = when {
        card.tool.isDmsTool -> HandoffOpen.DmsTool(DmsHandoffRules.windowFor(card.tool), card.taskId)

        card.tool == HandoffTool.SUBMISSION_ANALYZER -> HandoffOpen.Analyzer(
            card.sessionId ?: card.threadId,
            card.mode?.takeIf {
                it.isNotBlank() &&
                    it != "full"
            },
        )

        !card.route.isNullOrBlank() -> HandoffOpen.Route(card.route)

        card.tool == HandoffTool.CONTRACT_ANALYSIS && !card.analysisId.isNullOrBlank() -> HandoffOpen.Route("/contract-analysis/${card.analysisId}")

        else -> HandoffOpen.Nothing
    }
}

/** Rule 25: the preview opens the first time a draft appears during this thread's session, not on history load. */
object DraftPreviewRules {
    fun shouldAutoOpen(seenDraftThisSession: Boolean, fromHistoryLoad: Boolean, card: DraftDocumentCard?): Boolean =
        card != null && !seenDraftThisSession && !fromHistoryLoad
}

/** Rule 26: `proDraftDocument` — Pro artifacts always need a signed URL; Fill needs `can_replace` and a template. */
object DraftDocumentRules {
    fun fromArtifact(artifact: DraftingArtifact, status: String = "ready"): DraftDocumentCard = DraftDocumentCard(
        documentName = artifact.filename ?: "Generated document",
        status = status,
        fileUrl = artifact.downloadUrl,
        needsSignedUrl = true,
        templateId = artifact.templateId,
        placeholderCount = artifact.placeholderCount,
        canReplace = artifact.canReplace,
    )

    fun canFill(card: DraftDocumentCard): Boolean = card.canReplace && !card.templateId.isNullOrBlank()

    /** A `/media/…` path lives on the host root, not under `/api`; anything absolute passes through. */
    fun absoluteMediaUrl(mediaBaseUrl: String, url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return mediaBaseUrl.trimEnd('/') + (if (u.startsWith("/")) u else "/$u")
    }

    const val PARTIAL_SUCCESS_MESSAGE = "Draft text was generated, but no Word document was produced."
}

/** Rule 33: deleting a message is a client-side filter; clicking a user message copies it to the composer. */
object MessageEditRules {
    fun withoutMessage(messages: List<ChatMessage>, id: String): List<ChatMessage> = messages.filterNot { it.id == id }

    fun composerTextFor(message: ChatMessage): String? = message.content.takeIf { message.role == MessageRole.USER }
}

/** Rule 34: results dedupe by `case_id`, briefs by `case_id || id`. */
object DedupeRules {
    fun appendBrief(briefs: List<CaseBrief>, brief: CaseBrief): List<CaseBrief> = if (briefs.any { it.id == brief.id }) briefs else briefs + brief
}

/** Rule 35: the timers the page keeps, in milliseconds. */
object ChatTimers {
    const val CASE_SEARCH_PROGRESS_HIDE_MILLIS = 1_500L
    const val LIST_REFETCH_AFTER_CLOSE_MILLIS = 1_000L
    const val BRIEF_SOCKET_SAFETY_MILLIS = 5 * 60_000L
    const val BRIEFING_COUNTDOWN_TICK_MILLIS = 1_000L
    const val COPIED_RESET_MILLIS = 2_000L
    const val EXPORT_POLL_MILLIS = 5_000L
    const val EXPORT_MAX_ATTEMPTS = 30
    const val EDITOR_EXPORT_POLL_MILLIS = 3_000L
    const val BRIEF_FILE_POLL_MILLIS = 3_000L
}

/** Rows r24–r26: the Word export's document name and file name. */
object ExportNamingRules {
    fun documentName(brand: String, localeDate: String): String = "$brand Response - $localeDate"

    fun fileName(brand: String, isoDate: String): String = brand.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + "-response-$isoDate.docx"

    /** `Content-Disposition` filename, else `case_brief_<id>.<format>` (row r18). */
    fun briefFileName(contentDisposition: String?, caseId: String, format: BriefFileFormat): String {
        val fromHeader = contentDisposition?.let { Regex("filename\\*?=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1)?.trim() }
        return fromHeader?.removePrefix("UTF-8''")?.takeIf { it.isNotEmpty() } ?: "case_brief_$caseId.${format.wire}"
    }
}
