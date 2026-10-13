package com.lexken.lexkenai.feature.account.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.auth.AuthTokens
import com.lexken.lexkenai.core.auth.InMemoryTokenStore
import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.core.auth.session.SessionState
import com.lexken.lexkenai.core.model.Credits
import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.model.User
import com.lexken.lexkenai.core.model.UserId
import com.lexken.lexkenai.core.model.UserRole
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.FakeSessionRepository
import com.lexken.lexkenai.feature.account.data.InMemoryOnboardingStore
import com.lexken.lexkenai.feature.account.data.InMemoryProductUpdateStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** T3 for F1: the use-case compositions — the setup step machine, the wipes, the onboarding record, the product-update gate. */
class AccountFlowsTest {
    private val onboarding = InMemoryOnboardingStore()
    private val productUpdates = InMemoryProductUpdateStore()
    private val session =
        FakeSessionRepository(
            SessionState.SignedIn(User(UserId("u-1"), "Wanjiru Mwangi", "wanjiru@mwangiotieno.co.ke", UserRole.ADMIN, "Mwangi & Otieno"), Credits(120)),
        )
    private val tokens = InMemoryTokenStore(AuthTokens("lawris-jwt-1", "vf-refresh-1"))

    private val profile = LawFirmProfile(name = "Mwangi & Otieno Advocates", adminName = "Wanjiru Mwangi", adminEmail = "wanjiru@mwangiotieno.co.ke")
    private val png = FileInput("logo.png", "image/png", ByteArray(10))

    /** Records the calls and answers what the test scripted. */
    private class ScriptedLawFirms : LawFirmRepository {
        val calls = mutableListOf<String>()
        var patchFails = false
        var smtpFails = false

        override suspend fun profile() = Result.Success(LawFirmProfile())

        override suspend fun uploadLogo(file: FileInput) = Result.Success(LawFirmProfile(logoUrl = "u")).also { calls += "logo" }

        override suspend fun save(profile: LawFirmProfile, logo: FileInput?) = Result.Success(profile).also { calls += "save" }

        override suspend fun uploadSetupLogos(loginLogo: FileInput?, pdfLogo: FileInput?) =
            Result.Success(LawFirmProfile(logoUrl = "u")).also { calls += "logos(${listOfNotNull(loginLogo?.name, pdfLogo?.name).joinToString()})" }

        override suspend fun patch(profile: LawFirmProfile): Result<LawFirmProfile, LawFirmError> {
            calls += "patch"
            return if (patchFails) Result.Failure(LawFirmError.Rejected("admin_email must be a valid email.")) else Result.Success(profile)
        }

        override suspend fun saveSmtp(settings: SmtpSettings): EmptyResult<LawFirmError> {
            calls += "smtp"
            return if (smtpFails) Result.Failure(LawFirmError.Transport(DataError.Network.FORBIDDEN)) else Result.Success(Unit)
        }
    }

    @Test
    fun `the setup stepper skips the logo step without logos and the smtp step when blank, then clears the flags`() = runTest {
        onboarding.update { it.copy(showLawfirmSetup = true, lawfirmSetupSkipped = true, lawfirmSetupReminderCount = 2) }
        val firms = ScriptedLawFirms()

        val done = LawFirmSetupUseCase(firms, onboarding, session)(LawFirmSetup(profile)).getOrNull()!!

        assertThat(firms.calls).containsExactly("patch")
        assertThat(done.steps).containsExactly(LawFirmSetupStep.PROFILE)
        assertThat(onboarding.current()).isEqualTo(OnboardingFlags(showLawfirmSetup = false, lawfirmSetupSkipped = false, lawfirmSetupReminderCount = 0))
        assertThat(session.refreshCalls).isEqualTo(1)
    }

    @Test
    fun `the setup stepper runs all three steps in order when logos and smtp are given`() = runTest {
        val firms = ScriptedLawFirms()
        val smtp = SmtpSettings(host = "smtp.mwangiotieno.co.ke", user = "notices@mwangiotieno.co.ke", password = "x", fromEmail = "notices@mwangiotieno.co.ke")

        val done = LawFirmSetupUseCase(
            firms,
            onboarding,
            session,
        )(LawFirmSetup(profile, loginLogo = png, pdfLogo = FileInput("letterhead.png", "image/png", ByteArray(1)), smtp = smtp)).getOrNull()!!

        assertThat(firms.calls).containsExactly("logos(logo.png, letterhead.png)", "patch", "smtp")
        assertThat(done.steps).containsExactly(LawFirmSetupStep.LOGOS, LawFirmSetupStep.PROFILE, LawFirmSetupStep.SMTP)
    }

    @Test
    fun `a failed step leaves the setup flags untouched and never refreshes the session`() = runTest {
        onboarding.update { it.copy(showLawfirmSetup = true) }
        val firms = ScriptedLawFirms().apply { patchFails = true }

        val failed = LawFirmSetupUseCase(firms, onboarding, session)(LawFirmSetup(profile))

        assertThat(failed.errorOrNull()).isEqualTo(LawFirmError.Rejected("admin_email must be a valid email."))
        assertThat(onboarding.current()!!.showLawfirmSetup).isTrue()
        assertThat(session.refreshCalls).isEqualTo(0)
    }

    @Test
    fun `the stepper validates before any request, the setup rules and the 5 MB logo cap`() = runTest {
        val firms = ScriptedLawFirms()
        val useCase = LawFirmSetupUseCase(firms, onboarding, session)

        val noAdmin = useCase(LawFirmSetup(profile.copy(adminName = "")))
        val bigLogo = useCase(LawFirmSetup(profile, loginLogo = FileInput("logo.png", "image/png", ByteArray(5 * 1024 * 1024 + 1))))

        assertThat(noAdmin.errorOrNull()).isEqualTo(LawFirmError.Invalid(LawFirmProblem.ADMIN_NAME_REQUIRED))
        assertThat(bigLogo.errorOrNull()).isEqualTo(LawFirmError.Invalid(LawFirmProblem.LOGO_TOO_LARGE))
        assertThat(firms.calls).hasSize(0)
    }

    @Test
    fun `sign-out clears every local store before the session signs out`() = runTest {
        onboarding.update { it.copy(loginCount = 3) }
        productUpdates.markSeen("k-1")
        val order = mutableListOf<String>()
        val recorder = LocalStore { order += "store" }

        SignOutAndWipeUseCase(listOf(onboarding, productUpdates, recorder), session)()

        assertThat(onboarding.current()).isNull()
        assertThat(productUpdates.isSeen("k-1")).isFalse()
        assertThat(order).containsExactly("store")
        assertThat(session.signOutCalls).isEqualTo(1)
        assertThat(session.stateFlow.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `delete account wipes tokens and stores only after the server said 204`() = runTest {
        onboarding.update { it.copy(loginCount = 3) }
        var deletions = 0
        var answer: EmptyResult<DeleteAccountError> = Result.Failure(DeleteAccountError.Rejected("An error occurred during account deletion."))
        val repository = object : AccountRepository {
            override suspend fun deleteAccount(): EmptyResult<DeleteAccountError> = answer.also { deletions++ }
        }
        val useCase = DeleteAccountUseCase(repository, WipeLocalStateUseCase(listOf(onboarding, productUpdates), tokens, session))

        assertThat(useCase("delete my acc").errorOrNull()).isEqualTo(DeleteAccountError.PhraseMismatch)
        assertThat(deletions).isEqualTo(0)
        assertThat(useCase("delete my account").errorOrNull()!!).isInstanceOf(DeleteAccountError.Rejected::class)
        assertThat(onboarding.current()).isNotNull()
        assertThat(tokens.current()).isNotNull()

        answer = Result.Success(Unit)
        assertThat(useCase("Delete My Account")).isEqualTo(Result.Success(Unit))
        assertThat(deletions).isEqualTo(2)
        assertThat(onboarding.current()).isNull()
        assertThat(tokens.current()).isNull()
        assertThat(session.refreshCalls).isEqualTo(1)
    }

    @Test
    fun `the first sign-in creates the record with the three flags on, later sign-ins only count`() = runTest {
        val useCase = ObserveOnboardingUseCase(onboarding, session)

        val first = useCase.recordSignIn()
        val second = useCase.recordSignIn()

        assertThat(first).isEqualTo(OnboardingFlags(isFirstLogin = true, showTour = true, showLawfirmSetup = true, loginCount = 1))
        assertThat(second).isEqualTo(OnboardingFlags(isFirstLogin = false, showTour = true, showLawfirmSetup = true, loginCount = 2))
    }

    @Test
    fun `the ai tour decision writes the counter the web writes, and the dms tour completion flips its flags`() = runTest {
        onboarding.update { it.copy(loginCount = 1, showTour = true) }

        assertThat(DecideTourUseCase(onboarding)()).isEqualTo(TourKind.FULL)
        assertThat(onboarding.current()!!.loginCount).isEqualTo(2)
        assertThat(DecideTourUseCase(onboarding)()).isEqualTo(TourKind.NONE)
        assertThat(onboarding.current()!!.loginCount).isEqualTo(2)
        onboarding.update { it.copy(loginCount = 5) }
        assertThat(DecideTourUseCase(onboarding)()).isEqualTo(TourKind.NEW_TOOLS)
        assertThat(onboarding.current()!!.loginCount).isEqualTo(6)

        val completed = CompleteDmsTourUseCase(onboarding)()
        assertThat(completed.dmsTourCompleted).isTrue()
        assertThat(completed.showTour).isFalse()
        assertThat(SkipLawFirmSetupUseCase(onboarding)().lawfirmSetupSkipped).isTrue()
    }

    @Test
    fun `the product update shows once, the local flag is written before the remote call, and a failed POST does not resurface it`() = runTest {
        val update = ProductUpdate("k-2026-10", shouldShow = true, updates = listOf(ProductUpdateItem("Faster research", "Streams sources.")))
        var remoteMarks = 0
        val repository = object : ProductUpdatesRepository {
            override suspend fun latest() = update

            override suspend fun markSeen(updateKey: String): EmptyResult<DataError> {
                remoteMarks++
                return Result.Failure(DataError.Network.SERVER_ERROR)
            }
        }

        assertThat(CheckProductUpdateUseCase(repository, productUpdates)()).isEqualTo(update)
        val marked = MarkProductUpdateSeenUseCase(repository, productUpdates)("k-2026-10")
        assertThat(marked.errorOrNull()).isEqualTo(DataError.Network.SERVER_ERROR)
        assertThat(remoteMarks).isEqualTo(1)
        assertThat(productUpdates.isSeen("k-2026-10")).isTrue()
        assertThat(CheckProductUpdateUseCase(repository, productUpdates)()).isNull()
    }

    @Test
    fun `an invalid avatar never reaches the repository`() = runTest {
        var uploads = 0
        val repository = object : ProfileRepository {
            override suspend fun profile() = Result.Failure(DataError.Network.UNKNOWN)

            override suspend fun uploadAvatar(file: FileInput): Result<String?, DataError> = Result.Success("u").also { uploads++ }

            override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) = Result.Success(preferences)
        }

        val refused = UploadAvatarUseCase(repository)(FileInput("wanjiru.webp", "image/webp", ByteArray(10)))

        assertThat(refused.errorOrNull()).isEqualTo(AvatarUploadError.Invalid(AvatarProblem.TYPE))
        assertThat(uploads).isEqualTo(0)
        assertThat(UploadAvatarUseCase(repository)(png).getOrNull()).isEqualTo("u")
    }
}
