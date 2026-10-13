package com.lexken.lexkenai.feature.account.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap

/**
 * `users/views.py` `UserProfileRetrieveUpdateView` + `UserAvatarUploadView` (appendix auth #32–#34).
 * The profile's own `avatar_url` is always null (the serializer declares an ImageField over a
 * string property), so the URL comes from the upload's answer or `nav-info`.
 */
interface ProfileApi {
    @GET("users/profile/")
    suspend fun profile(): UserProfileDto

    @Multipart
    @POST("users/profile/avatar/")
    suspend fun uploadAvatar(
        @Part avatar: MultipartBody.Part,
    ): AvatarDto

    /** `notification_preferences` is merged into the stored dict server-side; the web sends all six keys. */
    @PATCH("users/profile/")
    suspend fun patch(
        @Body body: ProfilePatchBody,
    ): UserProfileDto
}

/**
 * `users/views.py` `LawFirmProfileView` (auth #35–#38): JSON or multipart, GET creates the row.
 * The only file fields the view reads are `logo` and `documents_logo`; the web stepper's
 * `login_logo` / `pdf_logo` names do not exist on the server (backend ask #5), so the stepper
 * sends the two that do.
 */
interface LawFirmApi {
    @GET("users/lawfirm-profile/")
    suspend fun profile(): LawFirmProfileDto

    @POST("users/lawfirm-profile/")
    suspend fun save(
        @Body body: LawFirmProfileBody,
    ): LawFirmProfileDto

    @Multipart
    @POST("users/lawfirm-profile/")
    suspend fun saveWithLogo(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part logo: MultipartBody.Part,
    ): LawFirmProfileDto

    @PATCH("users/lawfirm-profile/")
    suspend fun patch(
        @Body body: LawFirmProfileBody,
    ): LawFirmProfileDto

    @Multipart
    @PATCH("users/lawfirm-profile/")
    suspend fun patchLogo(
        @Part logo: MultipartBody.Part,
    ): LawFirmProfileDto

    /** Stepper step 1: either part may be absent; Retrofit drops a null part. */
    @Multipart
    @POST("users/lawfirm-profile/")
    suspend fun uploadLogos(
        @Part logo: MultipartBody.Part?,
        @Part documentsLogo: MultipartBody.Part?,
    ): LawFirmProfileDto
}

/** `users/views.py` `UserFeedbackView` / `UserBugReportView` (auth #41): `subject` ≤ 255 plus the text. */
interface SupportApi {
    @POST("users/feedback/")
    suspend fun feedback(
        @Body body: FeedbackBody,
    ): MessageDto

    @POST("users/report-bug/")
    suspend fun reportBug(
        @Body body: BugReportBody,
    ): MessageDto
}

/** `users/views.py` `DeleteAccountView` (auth #39): 204 — with a JSON body the server should not send. */
interface AccountDeletionApi {
    @DELETE("users/profile/delete_account/")
    suspend fun deleteAccount(): Response<Unit>
}

/** `ai/views.py` `ProductUpdateLatestView` / `ProductUpdateMarkSeenView` (auth #59, #60). */
interface ProductUpdatesApi {
    @GET("ai/product-updates/latest/")
    suspend fun latest(): ProductUpdateDto

    @POST("ai/product-updates/mark-seen/")
    suspend fun markSeen(
        @Body body: MarkSeenBody,
    ): MarkSeenDto
}

/** `pms/views.py` `SmtpConfigurationViewSet.create` (auth #38 step 3): 201 with the config, password never echoed. */
interface SmtpApi {
    @POST("pms/smtp-config/")
    suspend fun create(
        @Body body: SmtpConfigBody,
    ): SmtpConfigDto
}

// ── DTOs ────────────────────────────────────────────────────────────────────

@Serializable
data class UserProfileDto(
    val user: ProfileUserDto? = null,
    /** The stored dict as is — may be `{}`; values are booleans when the web wrote them. */
    @SerialName("notification_preferences") val notificationPreferences: JsonObject? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("preferred_language") val preferredLanguage: String? = null,
    @SerialName("user_timezone") val userTimezone: String? = null,
    /** The web reads `user.profile.preferred_language` too; tolerated in case a serializer nests them. */
    val profile: ProfileNestedDto? = null,
    @SerialName("personal_ai_credits_balance") val personalCredits: Int? = null,
)

@Serializable
data class ProfileUserDto(
    val id: JsonPrimitive? = null,
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("role_name") val roleName: String? = null,
    val profile: ProfileNestedDto? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class ProfileNestedDto(
    @SerialName("preferred_language") val preferredLanguage: String? = null,
    @SerialName("user_timezone") val userTimezone: String? = null,
)

@Serializable
data class ProfilePatchBody(
    @SerialName("notification_preferences") val notificationPreferences: Map<String, Boolean>,
)

@Serializable
data class AvatarDto(
    val message: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class LawFirmProfileDto(
    @SerialName("lawfirm_id") val lawfirmId: String? = null,
    @SerialName("lawfirm_name") val lawfirmName: String? = null,
    @SerialName("admin_name") val adminName: String? = null,
    @SerialName("admin_email") val adminEmail: String? = null,
    @SerialName("admin_tel") val adminTel: String? = null,
    /** An int, or the echoed string after a multipart save. */
    @SerialName("employee_count") val employeeCount: JsonPrimitive? = null,
    val address: String? = null,
    @SerialName("bank_details") val bankDetails: JsonElement? = null,
    @SerialName("technical_details") val technicalDetails: JsonElement? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("documents_logo_url") val documentsLogoUrl: String? = null,
)

@Serializable
data class LawFirmProfileBody(
    @SerialName("lawfirm_name") val lawfirmName: String,
    @SerialName("admin_name") val adminName: String? = null,
    @SerialName("admin_email") val adminEmail: String? = null,
    @SerialName("admin_tel") val adminTel: String? = null,
    val address: String? = null,
    @SerialName("employee_count") val employeeCount: Int? = null,
    @SerialName("bank_details") val bankDetails: JsonElement? = null,
    @SerialName("technical_details") val technicalDetails: JsonElement? = null,
)

@Serializable
data class FeedbackBody(val subject: String, val feedback: String)

@Serializable
data class BugReportBody(val subject: String, val report: String)

@Serializable
data class MessageDto(val message: String? = null)

@Serializable
data class ProductUpdateDto(
    @SerialName("update_key") val updateKey: String? = null,
    @SerialName("should_show") val shouldShow: Boolean? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val updates: List<ProductUpdateItemDto> = emptyList(),
)

@Serializable
data class ProductUpdateItemDto(val title: String? = null, val message: String? = null, val icon: String? = null)

@Serializable
data class MarkSeenBody(
    @SerialName("update_key") val updateKey: String,
)

@Serializable
data class MarkSeenDto(
    val success: Boolean? = null,
    @SerialName("update_key") val updateKey: String? = null,
    val seen: Boolean? = null,
    @SerialName("newly_marked") val newlyMarked: Boolean? = null,
)

@Serializable
data class SmtpConfigBody(
    @SerialName("email_host") val emailHost: String,
    @SerialName("email_port") val emailPort: Int,
    @SerialName("email_use_tls") val emailUseTls: Boolean,
    @SerialName("email_use_ssl") val emailUseSsl: Boolean,
    @SerialName("email_host_user") val emailHostUser: String,
    @SerialName("email_host_password") val emailHostPassword: String,
    @SerialName("default_from_email") val defaultFromEmail: String,
    @SerialName("cc_emails") val ccEmails: List<String>,
    @SerialName("bcc_emails") val bccEmails: List<String>,
    /** No default: the Json does not encode defaults and the web always sends the flag. */
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class SmtpConfigDto(
    val id: String? = null,
    @SerialName("email_host") val emailHost: String? = null,
    @SerialName("email_port") val emailPort: Int? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)
