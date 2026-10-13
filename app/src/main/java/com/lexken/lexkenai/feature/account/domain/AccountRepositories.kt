package com.lexken.lexkenai.feature.account.domain

import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    /** `GET users/profile/` (auth #32). */
    suspend fun profile(): Result<AccountProfile, DataError>

    /** `POST users/profile/avatar/` multipart `avatar` (auth #33) → the new `avatar_url`. */
    suspend fun uploadAvatar(file: FileInput): Result<String?, DataError>

    /** `PATCH users/profile/` with the full `notification_preferences` object (auth #34); answers the stored set. */
    suspend fun updateNotificationPreferences(preferences: NotificationPreferences): Result<NotificationPreferences, DataError>
}

interface LawFirmRepository {
    /** `GET users/lawfirm-profile/` (auth #35); the server creates the row on first read. */
    suspend fun profile(): Result<LawFirmProfile, DataError>

    /** `PATCH users/lawfirm-profile/` multipart `logo` (auth #36) → the full object. */
    suspend fun uploadLogo(file: FileInput): Result<LawFirmProfile, LawFirmError>

    /** `POST users/lawfirm-profile/` — JSON, or multipart with `logo` when a picture was picked (auth #37). */
    suspend fun save(profile: LawFirmProfile, logo: FileInput?): Result<LawFirmProfile, LawFirmError>

    /** Stepper step 1: `POST users/lawfirm-profile/` multipart `login_logo` / `pdf_logo` (auth #38). */
    suspend fun uploadSetupLogos(loginLogo: FileInput?, pdfLogo: FileInput?): Result<LawFirmProfile, LawFirmError>

    /** Stepper step 2: `PATCH users/lawfirm-profile/` JSON profile (auth #38). */
    suspend fun patch(profile: LawFirmProfile): Result<LawFirmProfile, LawFirmError>

    /** Stepper step 3: `POST pms/smtp-config/` (auth #38). */
    suspend fun saveSmtp(settings: SmtpSettings): EmptyResult<LawFirmError>
}

interface SupportRepository {
    /** `POST users/feedback/ {subject, feedback}` or `POST users/report-bug/ {subject, report}` (auth #41). */
    suspend fun send(kind: FeedbackKind, subject: String, message: String): EmptyResult<SupportError>
}

interface AccountRepository {
    /** `DELETE users/profile/delete_account/` (auth #39): only a 204 counts. */
    suspend fun deleteAccount(): EmptyResult<DeleteAccountError>
}

interface ProductUpdatesRepository {
    /** `GET ai/product-updates/latest/` (auth #59), fail-open to [ProductUpdate.NONE]. */
    suspend fun latest(): ProductUpdate

    /** `POST ai/product-updates/mark-seen/ {update_key}` (auth #60), non-fatal. */
    suspend fun markSeen(updateKey: String): EmptyResult<DataError>
}

/** The first-login flags, persisted per account; cleared on sign-out. */
interface OnboardingStore : LocalStore {
    val flags: Flow<OnboardingFlags?>

    suspend fun current(): OnboardingFlags?

    suspend fun update(transform: (OnboardingFlags) -> OnboardingFlags): OnboardingFlags
}

/** `seen_product_update_<key>` (rule 42), persisted per account. */
interface ProductUpdateStore : LocalStore {
    suspend fun isSeen(updateKey: String): Boolean

    suspend fun markSeen(updateKey: String)
}
