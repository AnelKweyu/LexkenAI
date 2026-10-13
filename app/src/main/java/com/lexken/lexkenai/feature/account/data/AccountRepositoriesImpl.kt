package com.lexken.lexkenai.feature.account.data

import com.lexken.lexkenai.core.auth.HttpFailure
import com.lexken.lexkenai.core.auth.httpCall
import com.lexken.lexkenai.core.auth.parseErrorBody
import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.network.safeCall
import com.lexken.lexkenai.core.network.toNetworkError
import com.lexken.lexkenai.core.network.toPart
import com.lexken.lexkenai.core.network.toTextPart
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.map
import com.lexken.lexkenai.core.result.mapError
import com.lexken.lexkenai.feature.account.domain.AccountProfile
import com.lexken.lexkenai.feature.account.domain.AccountRepository
import com.lexken.lexkenai.feature.account.domain.DeleteAccountError
import com.lexken.lexkenai.feature.account.domain.FeedbackKind
import com.lexken.lexkenai.feature.account.domain.LawFirmError
import com.lexken.lexkenai.feature.account.domain.LawFirmProfile
import com.lexken.lexkenai.feature.account.domain.LawFirmRepository
import com.lexken.lexkenai.feature.account.domain.NotificationPreferences
import com.lexken.lexkenai.feature.account.domain.ProductUpdate
import com.lexken.lexkenai.feature.account.domain.ProductUpdateItem
import com.lexken.lexkenai.feature.account.domain.ProductUpdatesRepository
import com.lexken.lexkenai.feature.account.domain.ProfileRepository
import com.lexken.lexkenai.feature.account.domain.SmtpSettings
import com.lexken.lexkenai.feature.account.domain.SupportError
import com.lexken.lexkenai.feature.account.domain.SupportRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.RequestBody
import retrofit2.Response
import java.net.ProtocolException

class RemoteProfileRepository(private val api: ProfileApi) : ProfileRepository {
    override suspend fun profile(): Result<AccountProfile, DataError> = safeCall { api.profile().toDomain() }

    override suspend fun uploadAvatar(file: FileInput): Result<String?, DataError> =
        safeCall { api.uploadAvatar(file.toPart("avatar")).avatarUrl }

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences): Result<NotificationPreferences, DataError> =
        safeCall { api.patch(ProfilePatchBody(preferences.toWire())).toDomain().notifications }
}

internal fun UserProfileDto.toDomain(): AccountProfile {
    val booleans = notificationPreferences.orEmpty().mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.booleanOrNull?.let { key to it }
    }.toMap()
    return AccountProfile(
        fullName = user?.fullName.orEmpty(),
        email = user?.email.orEmpty(),
        phone = user?.phoneNumber,
        roleName = user?.roleName,
        preferredLanguage = preferredLanguage ?: profile?.preferredLanguage ?: user?.profile?.preferredLanguage,
        timezone = userTimezone ?: profile?.userTimezone ?: user?.profile?.userTimezone,
        notifications = NotificationPreferences.fromWire(booleans),
        avatarUrl = avatarUrl ?: user?.avatarUrl,
    )
}

class RemoteLawFirmRepository(private val api: LawFirmApi, private val smtpApi: SmtpApi, private val json: Json) : LawFirmRepository {
    override suspend fun profile(): Result<LawFirmProfile, DataError> = safeCall { api.profile().toDomain() }

    override suspend fun uploadLogo(file: FileInput): Result<LawFirmProfile, LawFirmError> =
        httpCall(json) { api.patchLogo(file.toPart("logo")).toDomain() }.mapError { it.toLawFirmError() }

    override suspend fun save(profile: LawFirmProfile, logo: FileInput?): Result<LawFirmProfile, LawFirmError> =
        httpCall(json) {
            if (logo == null) {
                api.save(profile.toBody(json)).toDomain()
            } else {
                // The web JSON-stringifies the object fields when it has to go multipart.
                api.saveWithLogo(profile.toParts(), logo.toPart("logo")).toDomain()
            }
        }.mapError { it.toLawFirmError() }

    override suspend fun uploadSetupLogos(loginLogo: FileInput?, pdfLogo: FileInput?): Result<LawFirmProfile, LawFirmError> =
        httpCall(json) { api.uploadLogos(loginLogo?.toPart("logo"), pdfLogo?.toPart("documents_logo")).toDomain() }
            .mapError { it.toLawFirmError() }

    override suspend fun patch(profile: LawFirmProfile): Result<LawFirmProfile, LawFirmError> =
        httpCall(json) { api.patch(profile.toBody(json)).toDomain() }.mapError { it.toLawFirmError() }

    override suspend fun saveSmtp(settings: SmtpSettings): EmptyResult<LawFirmError> =
        httpCall(json) {
            smtpApi.create(
                SmtpConfigBody(
                    emailHost = settings.host,
                    emailPort = settings.port,
                    emailUseTls = settings.useTls,
                    emailUseSsl = settings.useSsl,
                    emailHostUser = settings.user,
                    emailHostPassword = settings.password,
                    defaultFromEmail = settings.fromEmail,
                    ccEmails = settings.cc,
                    bccEmails = settings.bcc,
                    isActive = true,
                ),
            )
        }.map { }.mapError { it.toLawFirmError() }
}

internal fun LawFirmProfileDto.toDomain() = LawFirmProfile(
    name = lawfirmName.orEmpty(),
    adminName = adminName.orEmpty(),
    adminEmail = adminEmail.orEmpty(),
    adminTel = adminTel.orEmpty(),
    employeeCount = employeeCount?.intOrNull ?: employeeCount?.contentOrNull?.toIntOrNull(),
    address = address.orEmpty(),
    logoUrl = logoUrl,
    bankDetails = bankDetails?.takeUnless { it is JsonNull }?.toString(),
    technicalDetails = technicalDetails?.takeUnless { it is JsonNull }?.toString(),
)

private fun LawFirmProfile.toBody(json: Json) = LawFirmProfileBody(
    lawfirmName = name,
    adminName = adminName.ifBlank { null },
    adminEmail = adminEmail.ifBlank { null },
    adminTel = adminTel.ifBlank { null },
    address = address.ifBlank { null },
    employeeCount = employeeCount,
    bankDetails = bankDetails?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() },
    technicalDetails = technicalDetails?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() },
)

private fun LawFirmProfile.toParts(): Map<String, RequestBody> = buildMap {
    put("lawfirm_name", name.toTextPart())
    if (adminName.isNotBlank()) put("admin_name", adminName.toTextPart())
    if (adminEmail.isNotBlank()) put("admin_email", adminEmail.toTextPart())
    if (adminTel.isNotBlank()) put("admin_tel", adminTel.toTextPart())
    if (address.isNotBlank()) put("address", address.toTextPart())
    employeeCount?.let { put("employee_count", it.toString().toTextPart()) }
    bankDetails?.let { put("bank_details", it.toTextPart()) }
    technicalDetails?.let { put("technical_details", it.toTextPart()) }
}

private fun HttpFailure.toLawFirmError(): LawFirmError =
    body?.firstSentence?.takeIf { network != DataError.Network.UNAUTHORIZED }?.let { LawFirmError.Rejected(it) }
        ?: LawFirmError.Transport(network)

class RemoteSupportRepository(private val api: SupportApi, private val json: Json) : SupportRepository {
    override suspend fun send(kind: FeedbackKind, subject: String, message: String): EmptyResult<SupportError> =
        httpCall(json) {
            when (kind) {
                FeedbackKind.FEEDBACK -> api.feedback(FeedbackBody(subject, message))
                FeedbackKind.BUG -> api.reportBug(BugReportBody(subject, message))
            }
        }.map { }.mapError { failure ->
            failure.body?.firstSentence?.takeIf { failure.network == DataError.Network.UNKNOWN }?.let { SupportError.Rejected(it) }
                ?: SupportError.Transport(failure.network)
        }
}

/**
 * `DeleteAccountView` answers 204 *with* a JSON body. OkHttp refuses a 204 that carries a
 * Content-Length (`ProtocolException: HTTP 204 had non-zero Content-Length`), so that exact
 * refusal is the success signal when it happens — the server has already deleted the account.
 */
class RemoteAccountRepository(private val api: AccountDeletionApi, private val json: Json) : AccountRepository {
    override suspend fun deleteAccount(): EmptyResult<DeleteAccountError> {
        val call = httpCall(json) {
            try {
                api.deleteAccount()
            } catch (e: ProtocolException) {
                if (e.message?.contains("204") == true) Response.success(204, Unit) else throw e
            }
        }
        return when (call) {
            is Result.Success -> {
                val response = call.data
                when {
                    response.code() == 204 -> {
                        Result.Success(Unit)
                    }

                    // Retrofit hands a Response<Unit> back unthrown whatever the status: read the body ourselves.
                    else -> {
                        val sentence = response.errorBody()?.string()?.let { parseErrorBody(json, it) }?.firstSentence
                        sentence?.let { Result.Failure(DeleteAccountError.Rejected(it)) }
                            ?: Result.Failure(DeleteAccountError.Transport(response.code().toNetworkError()))
                    }
                }
            }

            is Result.Failure -> {
                call.error.body?.firstSentence?.let { Result.Failure(DeleteAccountError.Rejected(it)) }
                    ?: Result.Failure(DeleteAccountError.Transport(call.error.network))
            }
        }
    }
}

class RemoteProductUpdatesRepository(private val api: ProductUpdatesApi) : ProductUpdatesRepository {
    override suspend fun latest(): ProductUpdate =
        when (val fetched = safeCall { api.latest() }) {
            is Result.Success -> fetched.data.toDomain()
            is Result.Failure -> ProductUpdate.NONE
        }

    override suspend fun markSeen(updateKey: String): EmptyResult<DataError> =
        safeCall { api.markSeen(MarkSeenBody(updateKey)) }.map { }
}

internal fun ProductUpdateDto.toDomain() = ProductUpdate(
    updateKey = updateKey.orEmpty(),
    shouldShow = shouldShow == true,
    title = title,
    subtitle = subtitle,
    updates = updates.map { ProductUpdateItem(it.title.orEmpty(), it.message.orEmpty(), it.icon) },
)
