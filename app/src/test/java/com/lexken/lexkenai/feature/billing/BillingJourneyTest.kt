package com.lexken.lexkenai.feature.billing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.lexken.lexkenai.core.auth.SignInOutcome
import com.lexken.lexkenai.core.auth.SignInUseCase
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.bodyText
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.core.testing.KoinTestRule
import com.lexken.lexkenai.feature.billing.domain.BuyCreditsUseCase
import com.lexken.lexkenai.feature.billing.domain.CreditsPurchase
import com.lexken.lexkenai.feature.billing.domain.CreditsWallRules
import com.lexken.lexkenai.feature.billing.domain.LoadCreditsBalanceUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadPlanCatalogueUseCase
import com.lexken.lexkenai.feature.billing.domain.PaymentFlow
import com.lexken.lexkenai.feature.billing.domain.PaymentInitiated
import com.lexken.lexkenai.feature.billing.domain.PaymentOutcome
import com.lexken.lexkenai.feature.billing.domain.PaymentProvider
import com.lexken.lexkenai.feature.billing.domain.PollPurchaseUseCase
import com.lexken.lexkenai.feature.billing.domain.PurchaseEvent
import com.lexken.lexkenai.feature.billing.domain.SubscribeUseCase
import com.lexken.lexkenai.feature.billing.domain.SubscriptionPurchase
import com.lexken.lexkenai.feature.billing.domain.ValidateDiscountUseCase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.dsl.module

/** T8 for F2: the two purchase journeys of the plan, through the real Koin graph against the contract server. */
class BillingJourneyTest {
    @Nested
    inner class Daraja {
        private val server = ContractServer()
        private val journeyModule = module {
            single(BillingQualifiers.PAYMENT_PROVIDER_NAME) { "daraja" }
            factory { BuyCreditsUseCase(payments = get(), subscriptions = get(), today = { "2026-10-05" }) }
        }

        @JvmField
        @RegisterExtension
        val koin = KoinTestRule(server, modules = listOf(billingModule, journeyModule))

        @Test
        fun `a Kisumu advocate tops up 500 credits by M-Pesa after hitting the credits wall`() = runTest {
            server.serve("oauth2.mobile-token", "auth.proxy-exchange-token", "users.profile.nav-info", "users.lawfirm-profile", "sub.current.pending-payment")
            // The session reads the balance once at sign-in, the top bar once more, and once again after the top-up.
            val fourCredits = """{"personal_ai_credits_balance":4,"subscription_ai_credits_balance":0,"ai_credits_reset_date":"2026-10-06"}"""
            server.serve(Host.LAWRIS, "GET", "/sub/ai-credits-balance/", body = fourCredits)
            server.serve(Host.LAWRIS, "GET", "/sub/ai-credits-balance/", body = fourCredits)
            server.serve(
                Host.LAWRIS,
                "GET",
                "/sub/ai-credits-balance/",
                body = """{"personal_ai_credits_balance":1004,"subscription_ai_credits_balance":0,"ai_credits_reset_date":"2026-10-06"}""",
            )
            server.serve("ai.session.turnstile-required", "ai.lawris-ai.insufficient-credits", "sub.payments.initiate_credit_purchase")
            server.serve("sub.payments.status.pending", "sub.payments.status.pending", "sub.payments.status.pending", "sub.payments.status.completed")
            assertThat(koin.get<PaymentProvider>()).isEqualTo(PaymentProvider.DARAJA)

            assertThat(koin.get<SignInUseCase>()("otieno@kisumuchambers.co.ke", "correct-horse-battery")).isEqualTo(Result.Success(SignInOutcome.SignedIn))
            assertThat(koin.get<LoadCreditsBalanceUseCase>()().getOrNull()!!.credits).isEqualTo(4)

            // The chat send through the authed client hits the credits wall: the 403 body opens the top-up with the balance it reports.
            val response = koin.get<OkHttpClient>().newCall(
                Request.Builder().url(
                    server.lawrisBaseUrl + "ai/lawris-ai/",
                ).post("""{"message":"Notice period"}""".toRequestBody("application/json".toMediaType())).build(),
            ).execute()
            assertThat(response.code).isEqualTo(403)
            val wall = CreditsWallRules.fromHttpBody(response.body.string())
            assertThat(wall).isNotNull()
            assertThat(wall!!.currentBalance).isEqualTo(4)
            assertThat(wall.requiredCredits).isEqualTo(10)

            val pushed = koin.get<BuyCreditsUseCase>()(CreditsPurchase(500, koin.get(), phoneNumber = "0722334455")).getOrNull()
            assertThat(pushed).isEqualTo(PaymentInitiated.PushSent("pay-1", "STK push sent"))
            assertThat(server.lastRequest(Host.LAWRIS, "POST", "/sub/payments/initiate_credit_purchase/").bodyText())
                .isEqualTo("""{"phone_number":"0722334455","amount":500,"transaction_desc":"AICreditTopUp"}""")

            val events = koin.get<PollPurchaseUseCase>()(PaymentProvider.DARAJA, "pay-1").toList()
            assertThat(events.filterIsInstance<PurchaseEvent.Pending>()).hasSize(3)
            assertThat((events.last() as PurchaseEvent.Settled).result).isEqualTo(PollPurchaseUseCase.Settled(PaymentOutcome.SUCCESS, "QGH7K2L9MN", null))
            assertThat(server.requests(Host.LAWRIS, "GET", "/sub/payments/status/pay-1/")).hasSize(4)

            assertThat(koin.get<LoadCreditsBalanceUseCase>()().getOrNull()!!.credits).isEqualTo(1004)
            assertThat(server.unrouted).hasSize(0)
        }
    }

    @Nested
    inner class Paystack {
        private val server = ContractServer()

        @JvmField
        @RegisterExtension
        val koin = KoinTestRule(server, modules = listOf(billingModule, module { single(BillingQualifiers.PAYMENT_PROVIDER_NAME) { "paystack" } }))

        @Test
        fun `a solo practitioner subscribes to Solo with a discount code via Paystack`() = runTest {
            server.serve(
                "oauth2.mobile-token",
                "auth.proxy-exchange-token",
                "users.profile.nav-info",
                "sub.ai-credits-balance",
                "users.lawfirm-profile",
                "sub.current",
            )
            server.serve("sub.plans", "sub.discounts.validate", "sub.payments.paystack.initiate")
            server.serve("sub.payments.paystack.status.pending", "sub.payments.paystack.status.cancelled", "sub.payments.paystack.status.success")

            assertThat(koin.get<SignInUseCase>()("njeri@njeriadvocates.co.ke", "correct-horse-battery")).isEqualTo(Result.Success(SignInOutcome.SignedIn))

            val catalogue = koin.get<LoadPlanCatalogueUseCase>()(currentTier = "PUB").getOrNull()!!
            assertThat(catalogue.map { it.tier }).containsExactly("PUB", "SOLO", "BSC", "ENT")
            val solo = catalogue.first { it.tier == "SOLO" }
            var flow = PaymentFlow().choosePlan(solo).typeCode("LEX50")

            val discount = koin.get<ValidateDiscountUseCase>()("LEX50", solo.id).getOrNull()!!
            flow = flow.applyDiscount(discount)
            assertThat(flow.priceDue).isEqualTo(1250.0)

            val started = koin.get<SubscribeUseCase>()(SubscriptionPurchase(solo.id, PaymentProvider.PAYSTACK, discountCode = "LEX50")).getOrNull()!!
            assertThat(started).isInstanceOf(PaymentInitiated.Checkout::class)
            assertThat((started as PaymentInitiated.Checkout).authorizationUrl).isEqualTo("https://checkout.paystack.com/lxk7d4e")
            assertThat(server.lastRequest(Host.LAWRIS, "POST", "/sub/payments/paystack/initiate/").bodyText())
                .isEqualTo("""{"plan_id":"c1e0a7b2-1111-4a1a-9b1b-000000000001","discount_code":"LEX50"}""")
            flow = flow.initiated(started.paymentId)

            val events = koin.get<PollPurchaseUseCase>()(PaymentProvider.PAYSTACK, started.paymentId).toList()
            // PENDING, then a CANCELLED that Paystack keeps polling through, then SUCCESS.
            assertThat(events.map { it::class.simpleName }).containsExactly("Pending", "Pending", "Settled")
            val settled = (events.last() as PurchaseEvent.Settled).result
            assertThat(settled.outcome).isEqualTo(PaymentOutcome.SUCCESS)
            assertThat(settled.reference).isEqualTo("lxk-ref-7d4e")
            flow = flow.settled(settled.outcome)
            assertThat(flow.step).isEqualTo(PaymentFlow.Step.OUTCOME)
            assertThat(server.unrouted).hasSize(0)
        }
    }
}
