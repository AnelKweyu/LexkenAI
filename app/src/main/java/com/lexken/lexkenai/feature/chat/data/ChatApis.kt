package com.lexken.lexkenai.feature.chat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/** `ai/views.py` `ConversationListCreateAPIView`, `ConversationDetailAPIView`, `MessageListAPIView`, `LegalAssistantView` (rows r4–r8). */
interface ChatApi {
    @GET("ai/conversations/")
    suspend fun conversations(): List<ConversationDto>

    /** An unknown or foreign id answers `[]`, not a 404. */
    @GET("ai/conversations/{id}/messages/")
    suspend fun messages(
        @Path("id") threadId: String,
    ): List<MessageDto>

    @PATCH("ai/conversations/{id}/")
    suspend fun patch(
        @Path("id") threadId: String,
        @Body body: ConversationPatchBody,
    ): ConversationDto

    @DELETE("ai/conversations/{id}/")
    suspend fun delete(
        @Path("id") threadId: String,
    ): Response<Unit>

    /**
     * The one send (r8): 16 string fields plus repeated `files`. `AISessionRequired` guards it, so
     * the AI-session interceptor attaches the headers; today the live answer is 403 (ask #1).
     * `Response` so the 200 (case search hit) and 202 both reach the repository with their status.
     */
    @Multipart
    @POST("ai/lawris-ai/")
    suspend fun send(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part files: List<MultipartBody.Part>,
    ): Response<SendDto>

    /** `rag/views.py` `CancelTaskView` / `CancelBriefingView` (r13/r14): plain JWT, no AI-session gate. */
    @POST("rag/cancel-task/")
    suspend fun cancelTask(
        @Body body: CancelTaskBody,
    ): CancelDto

    @POST("rag/cancel-briefing/")
    suspend fun cancelBriefing(
        @Body body: CancelBriefingBody,
    ): CancelDto
}

/** `BriefSelectedCasesView`, `CaseBriefLookupView`, `rag/views.py` cancel + history + follow-up, brief file routes (r9–r18). */
interface BriefApi {
    @POST("ai/brief-selected-cases/")
    suspend fun briefSelected(
        @Body body: JsonObject,
    ): BriefAcceptedDto

    /** 200 with the brief, **204 with no body** on a miss. */
    @GET("ai/case-brief/lookup/")
    suspend fun lookup(
        @Query("citation") citation: String,
        @Query("case_id") caseId: String,
        @Query("title") title: String,
    ): Response<JsonObject>

    @GET("rag/case/{id}/history/")
    suspend fun history(
        @Path("id") caseId: String,
    ): CaseHistoryDto

    @POST("rag/case/{id}/follow-up/")
    suspend fun followUp(
        @Path("id") caseId: String,
        @Body body: FollowUpBody,
    ): FollowUpAcceptedDto

    @GET("ai/generate-brief/{format}/{id}/")
    suspend fun generateFile(
        @Path("format") format: String,
        @Path("id") caseId: String,
    ): GenerateBriefDto

    /** 202 JSON while generating, 200 raw file when done — the repository branches on the status. */
    @GET("ai/brief-status/{taskId}/")
    suspend fun fileStatus(
        @Path("taskId") taskId: String,
    ): Response<ResponseBody>
}

/** `views_statute_detail.py` (r19/r20). */
interface StatuteApi {
    @POST("ai/statute-explain/")
    suspend fun explain(
        @Body body: JsonObject,
    ): StatuteAnswerDto
}

/** `FeedbackAPIView` — no trailing slash (r21/r23). */
interface FeedbackApi {
    @POST("ai/feedback-ai")
    suspend fun send(
        @Body body: FeedbackBody,
    ): FeedbackDto
}

/** `FormatTextToWordDocView`, `FormatTaskStatusView` and the drafting artifact route (r24–r29). */
interface ExportApi {
    @POST("ai/format-text-to-word/")
    suspend fun formatToWord(
        @Body body: FormatBody,
    ): FormatAcceptedDto

    @GET("ai/format-task-status/{taskId}/")
    suspend fun status(
        @Path("taskId") taskId: String,
    ): FormatStatusDto

    /**
     * `drafting_artifact.download_url` is `/api/drafting/artifacts/<id>/download/`; relative to the
     * `/api/` base it becomes the doubled `/api/api/drafting/…` nginx needs. JSON `{url, filename}`
     * for Spaces-backed storage, a raw file otherwise.
     */
    @GET
    suspend fun artifact(
        @Url relativeDownloadUrl: String,
    ): Response<ResponseBody>
}

/** `drafting/views.py` registry and playbooks (r38–r44), the doubled `api/` prefix family. */
interface DraftingApi {
    @GET("api/drafting/document-types/")
    suspend fun documentTypes(): DocumentTypesDto

    @GET("api/drafting/playbooks/{type}/")
    suspend fun playbook(
        @Path("type") documentType: String,
    ): PlaybookDto

    @PUT("api/drafting/playbooks/{type}/")
    suspend fun savePlaybook(
        @Path("type") documentType: String,
        @Body body: PlaybookSaveBody,
    ): PlaybookDto

    @DELETE("api/drafting/playbooks/{type}/")
    suspend fun resetPlaybook(
        @Path("type") documentType: String,
    ): PlaybookDto

    @GET("api/drafting/playbooks/{type}/versions/")
    suspend fun versions(
        @Path("type") documentType: String,
    ): PlaybookVersionsDto

    @GET("api/drafting/playbooks/{type}/versions/{versionId}/")
    suspend fun version(
        @Path("type") documentType: String,
        @Path("versionId") versionId: String,
    ): PlaybookVersionDetailDto

    @POST("api/drafting/playbooks/{type}/versions/{versionId}/restore/")
    suspend fun restore(
        @Path("type") documentType: String,
        @Path("versionId") versionId: String,
    ): PlaybookDto
}

/** `views_document_bundles.py` (r33). */
interface BundleApi {
    @POST("ai/document-bundles/add-documents/")
    suspend fun addDocuments(
        @Body body: AddDocumentsBody,
    ): AddDocumentsDto
}

// ── DTOs ────────────────────────────────────────────────────────────────────

@Serializable
data class ConversationDto(
    val id: String,
    val title: String? = null,
    @SerialName("last_updated") val lastUpdated: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("backend_thread_id") val backendThreadId: String? = null,
)

@Serializable
data class ConversationPatchBody(val title: String)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val metadata: JsonObject? = null,
    @SerialName("associated_cases_details") val associatedCases: List<JsonObject> = emptyList(),
    @SerialName("ragProgressSteps") val ragProgressSteps: List<String> = emptyList(),
)

/** 200/202 of `ai/lawris-ai/`: `thread_id` always; `task_id` for general/drafting/case search; RAG answers only `thread_id`. */
@Serializable
data class SendDto(
    @SerialName("thread_id") val threadId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val mode: String? = null,
    val status: String? = null,
    val source: String? = null,
    val message: String? = null,
    val error: String? = null,
    val detail: JsonElement? = null,
)

@Serializable
data class BriefAcceptedDto(
    val message: String? = null,
    @SerialName("group_task_id") val groupTaskId: String? = null,
)

@Serializable
data class CancelTaskBody(
    @SerialName("task_id") val taskId: String?,
    @SerialName("conversation_id") val conversationId: String,
)

@Serializable
data class CancelBriefingBody(
    @SerialName("conversation_id") val conversationId: String,
)

@Serializable
data class CancelDto(val status: String? = null, val message: String? = null)

@Serializable
data class CaseHistoryDto(
    @SerialName("thread_id") val threadId: String? = null,
    val messages: List<HistoryMessageDto> = emptyList(),
)

@Serializable
data class HistoryMessageDto(
    val id: String? = null,
    val role: String? = null,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class FollowUpBody(val query: String)

@Serializable
data class FollowUpAcceptedDto(
    val status: String? = null,
    val message: String? = null,
    @SerialName("thread_id") val threadId: String? = null,
)

@Serializable
data class GenerateBriefDto(
    @SerialName("task_id") val taskId: String? = null,
    val message: String? = null,
)

@Serializable
data class StatuteAnswerDto(val answer: String? = null)

@Serializable
data class FeedbackBody(
    val conversation: String,
    @SerialName("response_id") val responseId: String,
    @SerialName("feedback_type") val feedbackType: String,
    val prompt: String,
    val answer: String,
    val details: String? = null,
)

@Serializable
data class FeedbackDto(
    val id: String? = null,
    @SerialName("feedback_type") val feedbackType: String? = null,
)

@Serializable
data class FormatBody(
    @SerialName("raw_text") val rawText: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("document_name") val documentName: String,
)

@Serializable
data class FormatAcceptedDto(
    @SerialName("task_id") val taskId: String? = null,
)

/** Always 200; the task may record FAILURE inside `result` under a SUCCESS state — read `result.status` / `result.error`. */
@Serializable
data class FormatStatusDto(
    @SerialName("task_id") val taskId: String? = null,
    val status: String? = null,
    val result: FormatResultDto? = null,
)

@Serializable
data class FormatResultDto(
    val status: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    val error: String? = null,
)

@Serializable
data class SignedUrlDto(val url: String? = null, val filename: String? = null)

@Serializable
data class DocumentTypesDto(
    @SerialName("document_types") val documentTypes: List<DocumentTypeDto> = emptyList(),
    @SerialName("output_formats") val outputFormats: List<JsonElement> = emptyList(),
    @SerialName("default_output_format") val defaultOutputFormat: String? = null,
    @SerialName("can_edit_playbooks") val canEditPlaybooks: Boolean? = null,
    @SerialName("house_style_available") val houseStyleAvailable: Boolean? = null,
)

@Serializable
data class DocumentTypeDto(val key: String, val playbook: String? = null, val label: String? = null, val structured: Boolean? = null)

@Serializable
data class PlaybookDto(
    val title: String? = null,
    val content: String? = null,
    val version: Long? = null,
    @SerialName("can_edit") val canEdit: Boolean? = null,
    @SerialName("is_customized") val isCustomized: Boolean? = null,
)

@Serializable
data class PlaybookSaveBody(
    val content: String,
    @SerialName("expected_version") val expectedVersion: Long?,
)

@Serializable
data class PlaybookVersionsDto(
    @SerialName("current_version") val currentVersion: Long? = null,
    val versions: List<PlaybookVersionDto> = emptyList(),
)

@Serializable
data class PlaybookVersionDto(
    val id: String,
    val version: Long? = null,
    @SerialName("change_type") val changeType: String? = null,
    @SerialName("edited_by") val editedBy: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val stats: PlaybookStatsDto? = null,
)

@Serializable
data class PlaybookStatsDto(val additions: Int? = null, val deletions: Int? = null)

@Serializable
data class PlaybookVersionDetailDto(
    val version: Long? = null,
    @SerialName("change_type") val changeType: String? = null,
    @SerialName("edited_by") val editedBy: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val diff: String? = null,
    val content: String? = null,
)

@Serializable
data class AddDocumentsBody(
    @SerialName("option_id") val optionId: String,
    val documents: List<String>,
)

@Serializable
data class AddDocumentsDto(
    val status: String? = null,
    @SerialName("option_id") val optionId: String? = null,
    @SerialName("bundle_name") val bundleName: String? = null,
    val added: List<String> = emptyList(),
    @SerialName("already_present") val alreadyPresent: List<String> = emptyList(),
    val reason: String? = null,
    val error: String? = null,
)
