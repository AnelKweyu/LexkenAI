package com.lexken.lexkenai.feature.account

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.auth.SignInOutcome
import com.lexken.lexkenai.core.auth.SignInUseCase
import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.core.auth.session.ObserveSessionUseCase
import com.lexken.lexkenai.core.auth.session.SessionState
import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.multipartParts
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.core.testing.KoinTestRule
import com.lexken.lexkenai.feature.account.data.InMemoryOnboardingStore
import com.lexken.lexkenai.feature.account.data.InMemoryProductUpdateStore
import com.lexken.lexkenai.feature.account.domain.CheckProductUpdateUseCase
import com.lexken.lexkenai.feature.account.domain.FeedbackKind
import com.lexken.lexkenai.feature.account.domain.LawFirmProfile
import com.lexken.lexkenai.feature.account.domain.LawFirmSetup
import com.lexken.lexkenai.feature.account.domain.LawFirmSetupStep
import com.lexken.lexkenai.feature.account.domain.LawFirmSetupUseCase
import com.lexken.lexkenai.feature.account.domain.MarkProductUpdateSeenUseCase
import com.lexken.lexkenai.feature.account.domain.ObserveOnboardingUseCase
import com.lexken.lexkenai.feature.account.domain.OnboardingStore
import com.lexken.lexkenai.feature.account.domain.ProductUpdateStore
import com.lexken.lexkenai.feature.account.domain.SendFeedbackUseCase
import com.lexken.lexkenai.feature.account.domain.SignOutAndWipeUseCase
import com.lexken.lexkenai.feature.account.domain.SmtpSettings
import com.lexken.lexkenai.feature.billing.BillingQualifiers
import com.lexken.lexkenai.feature.billing.billingModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * T8 for F1 — "a Nakuru conveyancing clerk sets up the firm": sign-in on the wire, the first-login
 * record, the three-call setup stepper, feedback, the product update seen once, and a sign-out
 * that leaves no account state behind. Everything resolves from the real Koin graph.
 */
class AccountJourneyTest {
    private val server = ContractServer()
    private val onboarding = InMemoryOnboardingStore()
    private val productUpdates = InMemoryProductUpdateStore()

    private val journeyModule = module {
        single(BillingQualifiers.PAYMENT_PROVIDER_NAME) { "paystack" }
        single<OnboardingStore> { onboarding } bind LocalStore::class
        single<ProductUpdateStore> { productUpdates } bind LocalStore::class
    }

    @JvmField
    @RegisterExtension
    val koin = KoinTestRule(server, modules = listOf(accountModule, billingModule, journeyModule))

    @Test
    fun `a Nakuru conveyancing clerk sets up the firm`() = runTest {
        server.serve(
            "oauth2.mobile-token",
            "auth.proxy-exchange-token",
            "users.profile.nav-info",
            "sub.ai-credits-balance",
            "ems.logout.success",
            "users.lawfirm-profile",
            "users.lawfirm-profile.updated",
            "users.lawfirm-profile.logo",
            "pms.smtp-config.created",
            "users.feedback.200",
            "ai.product-updates.latest",
            "ai.product-updates.mark-seen",
        )
        server.serve(Host.LAWRIS, "GET", "/sub/current/", body = """{"subscription":{"is_expired":false}}""")

        // Sign in → SignedIn with the firm name the server knows today.
        assertThat(koin.get<SignInUseCase>()("wanjiru@mwangiotieno.co.ke", "correct-horse-battery")).isEqualTo(Result.Success(SignInOutcome.SignedIn))
        val signedIn = koin.get<ObserveSessionUseCase>()().first { it is SessionState.SignedIn } as SessionState.SignedIn
        assertThat(signedIn.user.firmName).isEqualTo("Tier One Advocates")

        // First sign-in: the record is created with the three flags on.
        val flags = koin.get<ObserveOnboardingUseCase>().recordSignIn()
        assertThat(flags.isFirstLogin).isTrue()
        assertThat(flags.showLawfirmSetup).isTrue()
        assertThat(flags.loginCount).isEqualTo(1)

        // The stepper: logos, profile, SMTP — three calls in that order.
        val setup = LawFirmSetup(
            profile = LawFirmProfile(
                name = "Mwangi & Otieno Advocates",
                adminName = "Wanjiru Mwangi",
                adminEmail = "wanjiru@mwangiotieno.co.ke",
                adminTel = "+254722334455",
                address = "Kenyatta Avenue, Nakuru",
            ),
            loginLogo = FileInput("mwangi-otieno.png", "image/png", "logo".toByteArray()),
            pdfLogo = FileInput("letterhead.png", "image/png", "letterhead".toByteArray()),
            smtp = SmtpSettings(
                host = "smtp.mwangiotieno.co.ke",
                user = "notices@mwangiotieno.co.ke",
                password = "s3cret",
                fromEmail = "notices@mwangiotieno.co.ke",
                cc = listOf("records@mwangiotieno.co.ke"),
            ),
        )
        val done = koin.get<LawFirmSetupUseCase>()(setup).getOrNull()!!
        assertThat(done.steps).containsExactly(LawFirmSetupStep.LOGOS, LawFirmSetupStep.PROFILE, LawFirmSetupStep.SMTP)
        val stepperCalls = server.requests(Host.LAWRIS)
            .filter { (it.url.encodedPath.endsWith("/users/lawfirm-profile/") && it.method != "GET") || it.url.encodedPath.endsWith("/pms/smtp-config/") }
            .map { "${it.method} ${it.url.encodedPath}" }
        assertThat(stepperCalls).containsExactly("POST /api/users/lawfirm-profile/", "PATCH /api/users/lawfirm-profile/", "POST /api/pms/smtp-config/")
        assertThat(
            server.requests(Host.LAWRIS, "POST", "/users/lawfirm-profile/")[0].multipartParts().map {
                it.name
            },
        ).containsExactly("logo", "documents_logo")
        assertThat(onboarding.current()!!.showLawfirmSetup).isFalse()
        // The session re-read the firm profile and now carries the new name.
        val refreshed = koin.get<ObserveSessionUseCase>()().first() as SessionState.SignedIn
        assertThat(refreshed.user.firmName).isEqualTo("Tier One Advocates")

        // Feedback from the user menu.
        assertThat(
            koin.get<SendFeedbackUseCase>()(FeedbackKind.FEEDBACK, "Cause list export", "<p>The Nakuru cause list PDF is blank today.</p>"),
        ).isEqualTo(Result.Success(Unit))

        // The product update shows once.
        val update = koin.get<CheckProductUpdateUseCase>()()
        assertThat(update).isNotNull()
        koin.get<MarkProductUpdateSeenUseCase>()(update!!.updateKey)
        assertThat(koin.get<CheckProductUpdateUseCase>()()).isNull()
        assertThat(server.requests(Host.LAWRIS, "POST", "/ai/product-updates/mark-seen/")).hasSize(1)

        // Sign-out leaves nothing behind.
        koin.get<SignOutAndWipeUseCase>()()
        assertThat(koin.get<ObserveSessionUseCase>()().first()).isEqualTo(SessionState.SignedOut)
        assertThat(koin.tokenStore.current()).isNull()
        assertThat(onboarding.current()).isNull()
        assertThat(productUpdates.isSeen(update.updateKey)).isFalse()
        assertThat(server.unrouted).hasSize(0)
    }
}
