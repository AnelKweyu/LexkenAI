package com.lexken.lexkenai.feature.chat.domain

import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Error
import com.lexken.lexkenai.feature.billing.domain.CreditsWall

// ── Conversations (appendix chat rows r4–r7, rule 18) ───────────────────────

/** The workflow a thread came from, read off the server's title prefix (`conversationDisplay.js`). */
enum class ConversationKind(val prefix: String) {
    INTERNAL_DB("[DB]"),
    CASE_SEARCH("[Search]"),
    DRAFTING("[Drafting]"),
    GENERAL_AI("[General AI]"),
    PLEADINGS_ANALYSIS("[Analysis]"),
    CONTRACT_ANALYSIS("[Contract]"),
}

data class Conversation(
    val id: String,
    /** The server's title as stored, prefix included. */
    val rawTitle: String?,
    /** `getConversationTitle`: the prefix stripped; `Conversation <id>` when blank. */
    val title: String,
    val kind: ConversationKind?,
    /** `last_updated`, ISO as the server sends it. */
    val lastUpdated: String?,
    val lastUpdatedMillis: Long?,
)

enum class ConversationGroup { TODAY, YESTERDAY, PREVIOUS_7_DAYS, PREVIOUS_30_DAYS, OLDER }

// ── Messages and the cards rebuilt from `metadata` (row r5, `AnswerContainer_v2.jsx`) ─

enum class MessageRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val createdAt: String? = null,
    val cards: AnswerCards = AnswerCards(),
    /** `ragProgressSteps` persisted with the answer (and the live progress while streaming). */
    val progressSteps: List<String> = emptyList(),
    /** Briefs attached to the answer (`associated_cases_details` / `extractedCases`). */
    val extractedCases: List<CaseBrief> = emptyList(),
    /** The documents a user turn carried (`metadata.files`), names only. */
    val attachmentNames: List<String> = emptyList(),
    /** `isProcessingCases` — the bubble shows its progress list while true. */
    val isProcessing: Boolean = false,
    /** The drafting run this answer belongs to (rule 11). */
    val draftingRunId: String? = null,
    /** The last progress `sequence` seen for [draftingRunId] (rule 11). */
    val lastSequence: Long? = null,
    /** The analyzer's own persisted result (`metadata.type == "submission_analysis"`). */
    val isSubmissionAnalysis: Boolean = false,
)

data class AnswerCards(
    val caseSources: List<CaseSourceCard> = emptyList(),
    val statuteSources: List<StatuteSourceCard> = emptyList(),
    val webSources: List<WebSource> = emptyList(),
    val searchQueries: List<String> = emptyList(),
    val contractHandoff: ToolHandoffCard? = null,
    val submissionHandoff: ToolHandoffCard? = null,
    val dmsHandoff: ToolHandoffCard? = null,
    val documentBundleOffer: DocumentBundleOffer? = null,
    val bundleAdditionOffer: BundleAdditionOffer? = null,
    val documentGeneration: DocumentGenerationCard? = null,
    val draftDocument: DraftDocumentCard? = null,
    val draftingArtifact: DraftingArtifact? = null,
    val submissionAnalysis: SubmissionAnalysisCard? = null,
    val briefingConfirm: BriefingConfirmCard? = null,
    /** `rag_analysis_complete`: the cases the RAG answer mentioned, offered for briefing (rule 14). */
    val mentionedCases: List<MentionedCase> = emptyList(),
) {
    val isEmpty: Boolean
        get() = caseSources.isEmpty() && statuteSources.isEmpty() && webSources.isEmpty() && contractHandoff == null &&
            submissionHandoff == null && dmsHandoff == null && documentBundleOffer == null && bundleAdditionOffer == null &&
            documentGeneration == null && draftDocument == null && draftingArtifact == null && submissionAnalysis == null &&
            briefingConfirm == null && mentionedCases.isEmpty()
}

data class CaseSourceCard(
    /** `case_id || id`, else derived from the citation (rule 15's busy key uses the same chain). */
    val id: String,
    val title: String,
    val citation: String,
    val court: String? = null,
    val judgmentDate: String? = null,
    val usageNote: String? = null,
    val pageUrl: String? = null,
    val pdfUrl: String? = null,
    /** The object as received — `brief-selected-cases` and the lookup send it back verbatim. */
    val rawJson: String? = null,
)

data class StatuteSourceCard(
    val id: String,
    val title: String,
    val citation: String,
    /** "s. N — Title" labels from `primary_sections`. */
    val provisions: List<String> = emptyList(),
    val snippet: String? = null,
    val pageUrl: String? = null,
    val pdfUrl: String? = null,
    val fromMemory: Boolean = false,
    /** The object as received — `ai/statute-explain/ {source}` takes it verbatim (rows r19/r20). */
    val rawJson: String? = null,
)

data class WebSource(val uri: String, val title: String, val snippets: List<String> = emptyList()) {
    val domain: String get() = uri.removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
}

/** `ToolHandoffCard.jsx` + the DMS tool catalogue (rule 23). */
enum class HandoffTool(val wire: String) {
    CONTRACT_ANALYSIS("contractual_analysis"),
    SUBMISSION_ANALYZER("submission_analyzer"),
    BUNDLE_OF_AUTHORITIES("bundle_of_authorities"),
    VERIFY_CITATIONS("verify_citations"),
    OCR("ocr"),
    PAGINATION("pagination"),
    TENTH_LINING("tenth_lining"),
    TENTH_LINING_WITH_PAGINATION("tenth_lining_with_pagination"),
    PDF_TO_WORD("pdf_to_word"),
    WORD_TO_PDF("word_to_pdf"),
    UNKNOWN(""),
    ;

    val isDmsTool: Boolean get() = this in setOf(OCR, PAGINATION, TENTH_LINING, TENTH_LINING_WITH_PAGINATION, PDF_TO_WORD, WORD_TO_PDF)
}

data class ToolHandoffCard(
    val tool: HandoffTool,
    /** queued · started · running · complete · error — as the web's status strings. */
    val status: String,
    val mode: String? = null,
    val route: String? = null,
    val documentName: String? = null,
    val analysisId: String? = null,
    val sessionId: String? = null,
    val threadId: String? = null,
    val taskId: String? = null,
    val message: String? = null,
    val progress: Int? = null,
    val stage: String? = null,
    val outputUrl: String? = null,
)

data class BundleDocument(val name: String, val required: Boolean = false)

data class DocumentBundleOffer(
    val optionId: String,
    val optionName: String,
    val documents: List<BundleDocument>,
    val suggestedDocuments: List<String> = emptyList(),
    /** Caps the selection; 0 = none (rule 19). Null = uncapped. */
    val defaultDocumentLimit: Int? = null,
    val pending: Boolean = true,
)

data class BundleAdditionOffer(
    val optionId: String,
    val optionName: String,
    val documents: List<String>,
    val currentBundle: List<String> = emptyList(),
    val proposedBundle: List<String> = emptyList(),
    val pending: Boolean = true,
)

data class DocumentGenerationCard(
    val optionId: String? = null,
    val optionName: String,
    val documents: List<String> = emptyList(),
    val runId: String? = null,
    val taskId: String? = null,
    /** running · complete · failed · unknown ("Drafted") — rule 21. */
    val status: String = "unknown",
)

/** `drafting.file` / `metadata.drafting_artifact` — a Pro-Drafter artifact (rule 26). */
data class DraftingArtifact(
    val id: String?,
    val filename: String?,
    val downloadUrl: String?,
    val sizeBytes: Long?,
    val templateId: String?,
    val templateStatus: String?,
    val placeholderCount: Int,
    val canReplace: Boolean,
    val formSchemaUrl: String?,
)

data class DraftDocumentCard(
    val documentName: String,
    /** processing · ready · error */
    val status: String,
    /** The download target: a signed-URL endpoint when [needsSignedUrl], else a host-root media path. */
    val fileUrl: String? = null,
    val needsSignedUrl: Boolean = false,
    val templateId: String? = null,
    val placeholderCount: Int = 0,
    val canReplace: Boolean = false,
    val messageId: String? = null,
    val errorMessage: String? = null,
)

data class SubmissionAnalysisCard(
    val documentName: String? = null,
    val casesFound: Int = 0,
    val verified: Int = 0,
    val unverified: Int = 0,
    val alternatives: Int = 0,
    val verifiedCases: List<String> = emptyList(),
    val zipUrl: String? = null,
    val listPdfUrl: String? = null,
    val listDocxUrl: String? = null,
)

/** `tool.briefing_confirm` (rule 14): ≤5 shown, first 2 pre-selected, `timeout_seconds` countdown. */
data class BriefingConfirmCard(
    val cases: List<String>,
    val timeoutSeconds: Int,
    val messageId: String?,
)

/** One entry of `rag_analysis_complete.mentioned_cases` (the selection card). */
data class MentionedCase(val name: String, val rawJson: String)

/** `extracted_case_result` / `associated_cases_details`: a brief attached to an answer. */
data class CaseBrief(
    /** `case_id || id` — the dedupe key (rule 34) and the follow-up key. */
    val id: String,
    val title: String,
    val citation: String?,
    val summary: String?,
    val brief: String?,
    val url: String?,
    val pdfUrl: String?,
    val court: String?,
    val fromCache: Boolean = false,
    val rawJson: String? = null,
)

// ── Composer (rows r8, rules 1–7) ────────────────────────────────────────────

/** The five mutually exclusive modes (rule 3). */
enum class ChatMode { GENERAL_AI, INTERNAL_DB, DRAFTING_ASSISTANT, CASE_SEARCH, OLD_CASE_SEARCH }

/** `researchPresets.js` (rule 4). */
enum class ResearchPreset { INSTANT, FAST, PRO }

enum class PrecedentEngine(val wire: String) { V2("v2"), V3("v3") }

enum class DraftingEffort(val wire: String) { OFF("off"), MEDIUM("medium"), HIGH("high"), VERY_HIGH("very_high") }

enum class DraftingEngine(val wire: String) { V2("v2"), V3("v3") }

/** Everything the composer sends besides the prompt and the files — the web's state, with its defaults (rules 3–5, 8). */
data class ComposerSettings(
    val mode: ChatMode = ChatMode.GENERAL_AI,
    val useWebSources: Boolean = true,
    val precedentEngine: PrecedentEngine = PrecedentEngine.V3,
    val draftingEffort: DraftingEffort = DraftingEffort.MEDIUM,
    val draftingEngine: DraftingEngine = DraftingEngine.V2,
    /** `2 | 4 | 8 | 0` (0 = whole bundle); no UI control on the web, always 2. */
    val documentsPerRun: Int = 2,
    /** Registry key, e.g. `land_sale_agreement`; empty when none. */
    val draftingDocumentType: String = "",
    val modelChoice: String = DEFAULT_MODEL,
) {
    companion object {
        /** The only value the web effectively sends (the model dropdown is visually hidden). */
        const val DEFAULT_MODEL = "lawris-ai"
        const val OUTPUT_FORMAT = "docx"
        val DOCUMENTS_PER_RUN_OPTIONS = listOf(2, 4, 8, 0)
    }
}

/** One turn as the composer sends it (`handlePromptSubmit`). */
data class SendRequest(
    val prompt: String,
    /** `activeThreadId` — `""` for a new thread (the server returns the id to adopt). */
    val threadId: String?,
    val settings: ComposerSettings = ComposerSettings(),
    val attachments: List<FileInput> = emptyList(),
    /** `transcribe` — never toggled on the web. */
    val transcribe: Boolean = false,
    /** Retry re-adds only the assistant row (rule 9). */
    val isRetry: Boolean = false,
) {
    /** The derived flow flags (LawrisAI.jsx:1026-1035): which reducer branch runs. */
    val flow: TurnFlow
        get() = when (settings.mode) {
            ChatMode.INTERNAL_DB -> TurnFlow.RAG
            ChatMode.CASE_SEARCH, ChatMode.OLD_CASE_SEARCH -> TurnFlow.CASE_SEARCH
            ChatMode.GENERAL_AI, ChatMode.DRAFTING_ASSISTANT -> TurnFlow.GENERAL
        }

    /** The 16 string form fields of `POST ai/lawris-ai/`, in the web's order; `files` ride separately. */
    fun formFields(): LinkedHashMap<String, String> = linkedMapOf(
        "prompt" to prompt.trim(),
        "thread_id" to threadId.orEmpty(),
        "case_search" to (settings.mode == ChatMode.CASE_SEARCH).toString(),
        "old_case_search" to (settings.mode == ChatMode.OLD_CASE_SEARCH).toString(),
        "drafting_assistant" to (settings.mode == ChatMode.DRAFTING_ASSISTANT).toString(),
        "transcribe" to transcribe.toString(),
        "use_internal_db" to (settings.mode == ChatMode.INTERNAL_DB).toString(),
        "use_internal_db_version" to settings.precedentEngine.wire,
        "drafting_effort" to settings.draftingEffort.wire,
        "drafting_engine" to settings.draftingEngine.wire,
        "documents_per_run" to settings.documentsPerRun.toString(),
        "drafting_document_type" to settings.draftingDocumentType,
        "drafting_output_format" to ComposerSettings.OUTPUT_FORMAT,
        "drafting_post_fill" to "false",
        "use_web_sources" to settings.useWebSources.toString(),
        "model_choice" to settings.modelChoice,
    )
}

/** Which reducer branch a turn runs (rule 9's placeholders differ too). */
enum class TurnFlow { RAG, GENERAL, CASE_SEARCH }

/** `POST ai/lawris-ai/` 200/202 — `thread_id` to adopt and, for case search, the `task_id`. */
data class SendAccepted(val threadId: String?, val taskId: String?, val status: Int)

sealed interface SendError : Error {
    /** The route's own sentence (`data.error || data.detail`), or "Unexpected response status". */
    data class Rejected(val message: String) : SendError

    /** 403 `ai_session_required` — the gate behind backend ask #1. */
    data object SessionChallenge : SendError

    data class InsufficientCredits(val wall: CreditsWall) : SendError

    data object PlanRestricted : SendError

    data class NoSubscription(val wall: CreditsWall?) : SendError

    data class Transport(val error: DataError) : SendError
}

// ── Briefing, follow-up, files (rows r9–r18) ───────────────────────────────

/** The three `brief-selected-cases` call sites, with their differing bodies. */
sealed interface BriefRequest {
    val threadId: String

    /** RAG selection card: `{user_query, thread_id, selected_cases: object[]}`. */
    data class Selection(override val threadId: String, val userQuery: String, val selectedCasesJson: List<String>) : BriefRequest

    /** Auto-brief confirm: `{thread_id, assistant_message_id, selected_cases: names[], case_search_new_mode: true, old_case_search_old_mode}`. */
    data class AutoConfirm(
        override val threadId: String,
        val assistantMessageId: String?,
        val caseNames: List<String>,
        val oldCaseSearch: Boolean,
    ) : BriefRequest

    /** Source card / citation chip: one source, `source_mode: "2rah_v2_full_case"`. */
    data class Source(override val threadId: String, val assistantMessageId: String?, val sourceJson: String) : BriefRequest
}

data class CaseBriefHistory(val threadId: String?, val messages: List<ChatMessage>)

sealed interface FollowUpEvent {
    data class Progress(val content: String) : FollowUpEvent

    data class Final(val content: String, val messageId: String?) : FollowUpEvent

    data class Failed(val content: String) : FollowUpEvent
}

enum class BriefFileFormat(val wire: String) { PDF("pdf"), DOCX("docx") }

sealed interface BriefFileStatus {
    data object Generating : BriefFileStatus

    data class Ready(val bytes: ByteArray, val filename: String) : BriefFileStatus

    data class Failed(val message: String?) : BriefFileStatus
}

// ── Answer actions (rows r19–r29) ───────────────────────────────────────────

enum class AnswerFeedback(val wire: String) { LIKE("like"), DISLIKE("dislike") }

data class FeedbackRequest(
    val conversationId: String,
    val responseId: String,
    val type: AnswerFeedback,
    val prompt: String,
    val answer: String,
    val details: String? = null,
)

sealed interface ExportStatus {
    data object Pending : ExportStatus

    data class Success(val fileUrl: String) : ExportStatus

    data class Failure(val error: String?) : ExportStatus
}

data class ExportedDocument(val bytes: ByteArray, val filename: String)

sealed interface ExportError : Error {
    data object NoActiveConversation : ExportError

    data object TimedOut : ExportError

    data class Failed(val message: String?) : ExportError

    data class Transport(val error: DataError) : ExportError
}

// ── Drafting registry and playbooks (rows r38–r44) ──────────────────────────

data class DocumentType(val key: String, val playbook: String?, val label: String, val structured: Boolean)

data class DraftingRegistry(
    val documentTypes: List<DocumentType>,
    val outputFormats: List<String>,
    val defaultOutputFormat: String,
    val canEditPlaybooks: Boolean,
    val houseStyleAvailable: Boolean,
    /** True when the bundled fallback is in use (the request failed). */
    val fromFallback: Boolean = false,
)

data class Playbook(val content: String, val version: Long?, val canEdit: Boolean, val isCustomized: Boolean, val title: String?)

data class PlaybookVersion(
    val id: String,
    val version: Long?,
    val changeType: String?,
    val editedBy: String?,
    val createdAt: String?,
    val additions: Int?,
    val deletions: Int?,
)

data class PlaybookVersionDetail(
    val version: Long?,
    val changeType: String?,
    val editedBy: String?,
    val createdAt: String?,
    val diff: String?,
    val content: String?,
)

sealed interface PlaybookError : Error {
    /** 409 — edited elsewhere since `expected_version`. */
    data object Conflict : PlaybookError

    data class Rejected(val message: String) : PlaybookError

    data class Transport(val error: DataError) : PlaybookError
}

/** `POST ai/document-bundles/add-documents/` 200. */
data class BundleAdditionOutcome(val bundleName: String?, val added: List<String>, val alreadyPresent: List<String>)

/** A playbook skill as the Playbooks browser hands it over (rule 6). */
data class PlaybookSkill(val name: String, val slug: String?, val draftingDocumentType: String?)
