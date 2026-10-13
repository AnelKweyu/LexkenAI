package com.lexken.lexkenai.feature.account.domain

import com.lexken.lexkenai.core.auth.TokenStore
import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.core.auth.session.SessionRepository
import com.lexken.lexkenai.core.auth.session.SessionState
import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.map
import com.lexken.lexkenai.core.result.onSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LoadProfileUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(): Result<AccountProfile, DataError> = repository.profile()
}

class UploadAvatarUseCase(private val repository: ProfileRepository) {
    /** [AvatarRules] first, then `POST users/profile/avatar/`; answers the new URL. */
    suspend operator fun invoke(file: FileInput): Result<String?, AvatarUploadError> {
        AvatarRules.validate(file)?.let { return Result.Failure(AvatarUploadError.Invalid(it)) }
        return when (val uploaded = repository.uploadAvatar(file)) {
            is Result.Success -> uploaded
            is Result.Failure -> Result.Failure(AvatarUploadError.Transport(uploaded.error))
        }
    }
}

sealed interface AvatarUploadError : com.lexken.lexkenai.core.result.Error {
    data class Invalid(val problem: AvatarProblem) : AvatarUploadError

    data class Transport(val error: DataError) : AvatarUploadError
}

class UpdateNotificationPreferencesUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(preferences: NotificationPreferences): Result<NotificationPreferences, DataError> =
        repository.updateNotificationPreferences(preferences)
}

class LoadLawFirmProfileUseCase(private val repository: LawFirmRepository) {
    suspend operator fun invoke(): Result<LawFirmProfile, DataError> = repository.profile()
}

class SaveLawFirmProfileUseCase(private val repository: LawFirmRepository) {
    /** The Law Firm tab's save (auth #37): Yup rule 38, then JSON or multipart with the logo. */
    suspend operator fun invoke(profile: LawFirmProfile, logo: FileInput? = null): Result<LawFirmProfile, LawFirmError> {
        val trimmed = LawFirmProfileRules.trimmed(profile)
        LawFirmProfileRules.validate(trimmed)?.let { return Result.Failure(LawFirmError.Invalid(it)) }
        logo?.let(LogoRules::validate)?.let { return Result.Failure(LawFirmError.Invalid(it)) }
        return repository.save(trimmed, logo)
    }
}

class UploadLawFirmLogoUseCase(private val repository: LawFirmRepository) {
    /** The logo-only PATCH (auth #36). */
    suspend operator fun invoke(file: FileInput): Result<LawFirmProfile, LawFirmError> {
        LogoRules.validate(file)?.let { return Result.Failure(LawFirmError.Invalid(it)) }
        return repository.uploadLogo(file)
    }
}

/**
 * `LawFirmSetupStepper.jsx:198-236` (auth #38): step 1 posts the logos only when one was picked,
 * step 2 patches the profile, step 3 posts SMTP only when something was typed. On success the
 * three setup flags are cleared and the session's firm name catches up; a failed step leaves
 * the flags as they were so the stepper comes back next time.
 */
class LawFirmSetupUseCase(
    private val repository: LawFirmRepository,
    private val onboarding: OnboardingStore,
    private val session: SessionRepository,
) {
    data class Completed(val steps: List<LawFirmSetupStep>, val profile: LawFirmProfile)

    suspend operator fun invoke(setup: LawFirmSetup): Result<Completed, LawFirmError> {
        val profile = LawFirmProfileRules.trimmed(setup.profile)
        LawFirmProfileRules.validateSetup(profile)?.let { return Result.Failure(LawFirmError.Invalid(it)) }
        listOfNotNull(setup.loginLogo, setup.pdfLogo).forEach { logo ->
            SetupLogoRules.validate(logo)?.let { return Result.Failure(LawFirmError.Invalid(it)) }
        }
        val steps = mutableListOf<LawFirmSetupStep>()
        if (setup.loginLogo != null || setup.pdfLogo != null) {
            when (val uploaded = repository.uploadSetupLogos(setup.loginLogo, setup.pdfLogo)) {
                is Result.Failure -> return uploaded
                is Result.Success -> steps += LawFirmSetupStep.LOGOS
            }
        }
        val saved = when (val patched = repository.patch(profile)) {
            is Result.Failure -> return patched
            is Result.Success -> patched.data.also { steps += LawFirmSetupStep.PROFILE }
        }
        if (SmtpRules.shouldPost(setup.smtp)) {
            when (val smtp = repository.saveSmtp(setup.smtp)) {
                is Result.Failure -> return Result.Failure(smtp.error)
                is Result.Success -> steps += LawFirmSetupStep.SMTP
            }
        }
        onboarding.update { it.copy(showLawfirmSetup = false, lawfirmSetupSkipped = false, lawfirmSetupReminderCount = 0) }
        session.refresh()
        return Result.Success(Completed(steps, saved))
    }
}

/** "Skip" on the stepper (rule 35): remembered so the reminder logic can count. */
class SkipLawFirmSetupUseCase(private val onboarding: OnboardingStore) {
    suspend operator fun invoke(): OnboardingFlags =
        onboarding.update { it.copy(showLawfirmSetup = false, lawfirmSetupSkipped = true, lawfirmSetupReminderCount = 0) }
}

class SendFeedbackUseCase(private val repository: SupportRepository) {
    suspend operator fun invoke(kind: FeedbackKind, subject: String, message: String): EmptyResult<SupportError> {
        FeedbackRules.validate(subject, message)?.let { return Result.Failure(SupportError.Invalid(it)) }
        return repository.send(kind, subject.trim(), message)
    }
}

/**
 * Rule 39 and auth #39: the phrase gate, the DELETE, and — only on 204 — the same wipe the web
 * does with `localStorage.clear()`: tokens, every account store, the session.
 */
class DeleteAccountUseCase(
    private val repository: AccountRepository,
    private val wipe: WipeLocalStateUseCase,
) {
    suspend operator fun invoke(typedPhrase: String): EmptyResult<DeleteAccountError> {
        if (!DeleteAccountRules.matches(typedPhrase)) return Result.Failure(DeleteAccountError.PhraseMismatch)
        return repository.deleteAccount().onSuccess { wipe() }
    }
}

/** Clears every [LocalStore] and the tokens, then flips the session to signed-out without a server call. */
class WipeLocalStateUseCase(
    private val stores: List<LocalStore>,
    private val tokenStore: TokenStore,
    private val session: SessionRepository,
) {
    suspend operator fun invoke() {
        stores.forEach { it.clear() }
        tokenStore.clear()
        session.refresh()
    }
}

/**
 * The web's logout leaves `user_type`, `law_firm_*`, `subscription_info` and the first-login
 * flags behind (rule 36, a bug); mobile clears every account store, then revokes the session.
 */
class SignOutAndWipeUseCase(
    private val stores: List<LocalStore>,
    private val session: SessionRepository,
) {
    suspend operator fun invoke(): EmptyResult<DataError> {
        stores.forEach { it.clear() }
        return session.signOut()
    }
}

/**
 * The first-login flags. Mobile has no login response carrying them, so the first observed
 * signed-in session with no record persisted *is* the first login: the record is created with
 * `is_first_login`, `show_tour` and `show_lawfirm_setup` on (the web's `persistAuthSession`
 * values for a new firm account) and `loginCount` 1; later sign-ins only bump the counter.
 */
class ObserveOnboardingUseCase(
    private val onboarding: OnboardingStore,
    private val session: SessionRepository,
) {
    operator fun invoke(): Flow<OnboardingFlags?> = onboarding.flags

    /** Call once per sign-in (the app root does, when the session becomes SignedIn). */
    suspend fun recordSignIn(): OnboardingFlags {
        val current = onboarding.current()
        return if (current == null) {
            onboarding.update { it.copy(isFirstLogin = true, showTour = true, showLawfirmSetup = true, loginCount = 1) }
        } else {
            onboarding.update { it.copy(isFirstLogin = false, loginCount = it.loginCount + 1) }
        }
    }

    fun isSignedIn(): Flow<Boolean> = session.state.map { it is SessionState.SignedIn }
}

/** Rule 35: which `/ai` tour to run for this sign-in, and the counter write that goes with it. */
class DecideTourUseCase(private val onboarding: OnboardingStore) {
    suspend operator fun invoke(): TourKind {
        val flags = onboarding.current() ?: OnboardingFlags()
        val decision = TourRules.decide(flags.loginCount)
        if (decision.nextLoginCount != flags.loginCount) onboarding.update { it.copy(loginCount = decision.nextLoginCount) }
        return decision.tour
    }
}

/** `Dms.jsx:676`: the DMS tour finished — `dmsTourCompleted` on, `show_tour` off. */
class CompleteDmsTourUseCase(private val onboarding: OnboardingStore) {
    suspend operator fun invoke(): OnboardingFlags = onboarding.update { it.copy(dmsTourCompleted = true, showTour = false) }
}

/** Rule 42: fetch fail-open, gate on the local seen flag. Null when nothing is to be shown. */
class CheckProductUpdateUseCase(
    private val repository: ProductUpdatesRepository,
    private val store: ProductUpdateStore,
) {
    suspend operator fun invoke(): ProductUpdate? {
        val latest = repository.latest()
        return latest.takeIf { ProductUpdateGate.shouldShow(it, seenLocally = store.isSeen(it.updateKey)) }
    }
}

/** Rule 42: the local flag is written first so a failed POST never shows the banner twice. */
class MarkProductUpdateSeenUseCase(
    private val repository: ProductUpdatesRepository,
    private val store: ProductUpdateStore,
) {
    suspend operator fun invoke(updateKey: String): EmptyResult<DataError> {
        store.markSeen(updateKey)
        return repository.markSeen(updateKey).map { }
    }
}
