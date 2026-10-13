package com.lexken.lexkenai.feature.billing.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.auth.session.SessionApi
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.bodyText
import com.lexken.lexkenai.core.testing.ContractServer.Companion.jsonBody
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.feature.billing.domain.CreditsPurchase
import com.lexken.lexkenai.feature.billing.domain.DiscountError
import com.lexken.lexkenai.feature.billing.domain.ElevationDetails
import com.lexken.lexkenai.feature.billing.domain.InvoiceError
import com.lexken.lexkenai.feature.billing.domain.PaymentError
import com.lexken.lexkenai.feature.billing.domain.PaymentInitiated
import com.lexken.lexkenai.feature.billing.domain.PaymentProvider
import com.lexken.lexkenai.feature.billing.domain.PaymentStatus
import com.lexken.lexkenai.feature.billing.domain.SubscriptionPurchase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/** T2 for F2: every billing request row of the auth appendix (#42–#57), on the wire. */
class BillingContractTest {
    private val server = ContractServer()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
    private lateinit var retrofit: Retrofit

    @BeforeEach
    fun setUp() {
        server.start()
        retrofit = Retrofit.Builder().baseUrl(server.lawrisBaseUrl).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun subscriptions() = RemoteSubscriptionRepository(retrofit.create(), retrofit.create<SessionApi>(), json)
    private fun plans() = RemotePlansRepository(retrofit.create(), json)
    private fun payments() = RemotePaymentsRepository(retrofit.create(), json)
    private fun credits() = RemoteCreditsRepository(retrofit.create())

    @Test
    fun `current subscription maps the own plan, the employee assignment, and a 404 as none auth#42`() = runTest {
        server.serve("sub.current.pending-payment", "sub.current.assigned", "sub.current.none")
        val repository = subscriptions()

        val own = repository.current().getOrNull()!!
        val assigned = repository.current().getOrNull()!!
        val none = repository.current()

        assertThat(own.id).isEqualTo("sub-77")
        assertThat(own.tier).isEqualTo("SOLO")
        assertThat(own.isActive).isTrue()
        assertThat(own.isPaid).isFalse()
        assertThat(own.endDate).isEqualTo("2026-10-06")
        assertThat(own.creditsResetDate).isEqualTo("2026-10-06")
        assertThat(assigned.assigned).isTrue()
        assertThat(assigned.assignmentActive).isEqualTo(true)
        assertThat(assigned.tier).isEqualTo("BSC")
        assertThat(assigned.creditsBalance).isEqualTo(860)
        assertThat(none).isEqualTo(Result.Success(null))
    }

    @Test
    fun `the credits balance follows the web's or-chain and keeps the reset date auth#43`() = runTest {
        server.serve("sub.ai-credits-balance.shared")

        val balance = subscriptions().creditsBalance().getOrNull()!!

        assertThat(balance.credits).isEqualTo(200)
        assertThat(balance.resetsOn).isEqualTo("2026-10-20")
        assertThat(balance.sharedMonthlyLimit).isEqualTo(200)
        assertThat(balance.subscription).isNull()
    }

    @Test
    fun `payment history reads the bare array and a paginated results envelope auth#44`() = runTest {
        server.serve("sub.payments", "sub.payments.paginated")
        val repository = payments()

        val rows = repository.history().getOrNull()!!
        val paginated = repository.history().getOrNull()!!

        assertThat(rows.map { it.status }).containsExactly(PaymentStatus.SUCCESS, PaymentStatus.PENDING, PaymentStatus.FAILED)
        assertThat(rows[0].invoice!!.number).isEqualTo("INV-2026-000117")
        assertThat(rows[0].amountKes).isEqualTo(2500.0)
        assertThat(rows[0].invoiceAvailable).isTrue()
        assertThat(rows[1].invoiceAvailable).isFalse()
        assertThat(paginated).hasSize(1)
    }

    @Test
    fun `the invoice link carries the id in the path, and a 404 means not ready auth#45`() = runTest {
        server.serve("sub.invoices.download", "sub.invoices.download.not-ready")
        val repository = payments()

        val url = repository.invoiceDownloadUrl("b04c1d2e-3f4a-4b5c-8d6e-7f8a9b0c1d2e").getOrNull()
        val notReady = repository.invoiceDownloadUrl("b04c1d2e-3f4a-4b5c-8d6e-7f8a9b0c1d2e").errorOrNull()

        assertThat(server.requests(Host.LAWRIS, "GET", "/sub/invoices/b04c1d2e-3f4a-4b5c-8d6e-7f8a9b0c1d2e/download/")).hasSize(2)
        assertThat(url).isEqualTo("https://stage.lexken.ai/media/invoices/INV-2026-000117.pdf?signature=abc123")
        assertThat(notReady).isEqualTo(InvoiceError.NotReady)
    }

    @Test
    fun `plans parse the three duration shapes and decimal-string prices auth#46`() = runTest {
        server.serve("sub.plans")

        val plans = plans().plans().getOrNull()!!

        assertThat(plans.map { it.tier }).containsExactly("SOLO", "PRO", "PUB", "BSC")
        assertThat(plans.map { it.durationDays }).containsExactly(30, 30, 30, 90)
        assertThat(plans[0].priceKes).isEqualTo(2500.0)
        assertThat(plans[3].usersIncluded).isEqualTo(15)
        assertThat(plans[0].id).isEqualTo("c1e0a7b2-1111-4a1a-9b1b-000000000001")
    }

    @Test
    fun `discount validation posts code and plan_id, maps the price, and treats any 400 as invalid auth#47`() = runTest {
        server.serve("sub.discounts.validate", "sub.discounts.validate.invalid", "sub.discounts.validate.no-plan")
        val repository = plans()

        val applied = repository.validateDiscount("LEX50", "c1e0a7b2-1111-4a1a-9b1b-000000000001").getOrNull()!!
        val invalid = repository.validateDiscount("NOPE", "c1e0a7b2-1111-4a1a-9b1b-000000000001").errorOrNull()
        val noPlan = repository.validateDiscount("LEX50", "").errorOrNull()

        assertThat(server.requests(Host.LAWRIS, "POST", "/sub/discounts/validate/")[0].bodyText())
            .isEqualTo("""{"code":"LEX50","plan_id":"c1e0a7b2-1111-4a1a-9b1b-000000000001"}""")
        assertThat(applied.discountedPriceKes).isEqualTo(1250.0)
        assertThat(applied.percentage).isEqualTo(50.0)
        assertThat(invalid).isEqualTo(DiscountError.Invalid)
        assertThat(noPlan).isEqualTo(DiscountError.Invalid)
    }

    @Test
    fun `paystack subscribe sends plan, code and elevation but no phone, opens the checkout, and rebrands a refusal auth#48`() = runTest {
        server.serve("sub.payments.paystack.initiate", "sub.payments.paystack.initiate.error")
        val repository = payments()

        val started = repository.initiateSubscription(
            SubscriptionPurchase(
                "plan-solo",
                PaymentProvider.PAYSTACK,
                discountCode = "LEX50",
                elevation = ElevationDetails("Mwangi & Otieno Advocates", "P.105/1234/19"),
            ),
        ).getOrNull()!!
        val refused = repository.initiateSubscription(SubscriptionPurchase("plan-solo", PaymentProvider.PAYSTACK)).errorOrNull()

        val bodies = server.requests(Host.LAWRIS, "POST", "/sub/payments/paystack/initiate/").map { it.jsonBody().jsonObject }
        assertThat(bodies[0].keys).isEqualTo(setOf("plan_id", "discount_code", "elevation_details"))
        assertThat(
            bodies[0]["elevation_details"].toString(),
        ).isEqualTo("""{"law_firm_name":"Mwangi & Otieno Advocates","registration_number":"P.105/1234/19"}""")
        assertThat(bodies[1].keys).isEqualTo(setOf("plan_id"))
        assertThat(started).isInstanceOf(PaymentInitiated.Checkout::class)
        assertThat((started as PaymentInitiated.Checkout).authorizationUrl).isEqualTo("https://checkout.paystack.com/lxk7d4e")
        assertThat(started.paymentId).isEqualTo("7d4e5f6a-7b8c-4d9e-8f0a-1b2c3d4e5f6a")
        assertThat(refused).isEqualTo(PaymentError.Rejected("Pay checkout is unavailable."))
    }

    @Test
    fun `daraja subscribe sends the phone first and reports the push auth#49`() = runTest {
        server.serve("sub.payments.initiate")

        val started = payments().initiateSubscription(SubscriptionPurchase("plan-solo", PaymentProvider.DARAJA, phoneNumber = "0722334455")).getOrNull()!!

        assertThat(
            server.lastRequest(Host.LAWRIS, "POST", "/sub/payments/initiate/").bodyText(),
        ).isEqualTo("""{"phone_number":"0722334455","plan_id":"plan-solo"}""")
        assertThat(started).isEqualTo(PaymentInitiated.PushSent("7d4e5f6a-7b8c-4d9e-8f0a-1b2c3d4e5f6a", "STK Push sent. Check your phone and enter your PIN."))
    }

    @Test
    fun `paystack status reads the rail's route and the reference auth#50`() = runTest {
        server.serve("sub.payments.paystack.status.success")

        val read = payments().status(PaymentProvider.PAYSTACK, "7d4e5f6a-7b8c-4d9e-8f0a-1b2c3d4e5f6a").getOrNull()!!

        assertThat(server.requests(Host.LAWRIS, "GET", "/sub/payments/paystack/status/7d4e5f6a-7b8c-4d9e-8f0a-1b2c3d4e5f6a/")).hasSize(1)
        assertThat(read.rawStatus).isEqualTo("SUCCESS")
        assertThat(read.reference).isEqualTo("lxk-ref-7d4e")
    }

    @Test
    fun `daraja status reads the receipt, and an unknown payment is a not-found auth#51`() = runTest {
        server.serve("sub.payments.status.success", "sub.payments.status.not-found")
        val repository = payments()

        val read = repository.status(PaymentProvider.DARAJA, "7d4e5f6a-7b8c-4d9e-8f0a-1b2c3d4e5f6a").getOrNull()!!
        val missing = repository.status(PaymentProvider.DARAJA, "0b1c2d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e").errorOrNull()

        assertThat(read.receipt).isEqualTo("QHK8L3M0NP")
        assertThat(missing).isEqualTo(DataError.Network.NOT_FOUND)
    }

    @Test
    fun `paystack credits post the amount, and the subscription gate is its own outcome auth#52`() = runTest {
        server.serve("sub.payments.paystack.initiate_credit_purchase")
        server.serve(
            Host.LAWRIS,
            "POST",
            "/sub/payments/paystack/initiate_credit_purchase/",
            403,
            """{"error":"Credit top-ups require an active subscription.","error_code":"SUBSCRIPTION_REQUIRED",""" +
                """"message":"You need an active subscription to top up AI credits.","redirect_to":"/pay"}""",
        )
        val repository = payments()

        val started = repository.initiateCreditPurchase(CreditsPurchase(500, PaymentProvider.PAYSTACK)).getOrNull()
        val gated = repository.initiateCreditPurchase(CreditsPurchase(500, PaymentProvider.PAYSTACK)).errorOrNull()

        assertThat(server.requests(Host.LAWRIS, "POST", "/sub/payments/paystack/initiate_credit_purchase/")[0].bodyText()).isEqualTo("""{"amount":500}""")
        assertThat(started!!).isInstanceOf(PaymentInitiated.Checkout::class)
        assertThat(gated).isEqualTo(PaymentError.SubscriptionRequired)
    }

    @Test
    fun `daraja credits post phone, amount and the fixed transaction description auth#53`() = runTest {
        server.serve("sub.payments.initiate_credit_purchase")

        val started = payments().initiateCreditPurchase(CreditsPurchase(500, PaymentProvider.DARAJA, "0722334455")).getOrNull()

        assertThat(server.lastRequest(Host.LAWRIS, "POST", "/sub/payments/initiate_credit_purchase/").bodyText())
            .isEqualTo("""{"phone_number":"0722334455","amount":500,"transaction_desc":"AICreditTopUp"}""")
        assertThat(started).isEqualTo(PaymentInitiated.PushSent("pay-1", "STK push sent"))
    }

    @Test
    fun `credits history follows next as a relative path and flags refunds auth#54`() = runTest {
        server.serve("ai.ai-credits-history", "ai.ai-credits-history.page2")
        val repository = credits()

        val first = repository.history().getOrNull()!!
        val second = repository.history(first.nextPage).getOrNull()!!

        assertThat(first.nextPage).isEqualTo("ai/ai-credits-history/?page=2")
        assertThat(first.entries).hasSize(4)
        assertThat(first.entries[2].isRefund).isTrue()
        assertThat(first.entries[1].featureLabel).isEqualTo("Internal DB Precedent Retrieval")
        assertThat(second.nextPage).isNull()
        assertThat(second.entries.map { it.feature }).containsExactly("TEMPLATE_DOCUMENT", "CONTRACT_ANALYSIS")
        val requests = server.requests(Host.LAWRIS, "GET", "/ai/ai-credits-history/")
        assertThat(requests).hasSize(2)
        assertThat(requests[1].url.query).isEqualTo("page=2")
    }

    @Test
    fun `the discount-code request hits the route without a trailing slash and reads the 201 auth#56`() = runTest {
        server.serve("sub.request_discount_code", "sub.request_discount_code.no-email")
        val repository = subscriptions()

        val granted = repository.requestDiscountCode("wanjiru@mwangiotieno.co.ke").getOrNull()!!
        val refused = repository.requestDiscountCode("").errorOrNull()

        val request = server.lastRequest(Host.LAWRIS, "POST", "/sub/request_discount_code")
        assertThat(request.url.encodedPath).isEqualTo("/api/sub/request_discount_code")
        assertThat(request.bodyText()).isEqualTo("""{"email":""}""")
        assertThat(granted.code).isEqualTo("LEX50-AB12")
        assertThat(granted.validDays).isEqualTo(7)
        assertThat(refused).isEqualTo(DataError.Network.UNKNOWN)
    }

    @Test
    fun `the plan card reads the tier, the storage and seat labels, and the active users auth#57`() = runTest {
        server.serve("auth.api.my-plan-card")

        val card = plans().planCard().getOrNull()!!

        assertThat(card.tier).isEqualTo("SOLO")
        assertThat(card.storageLabel).isEqualTo("500MB")
        assertThat(card.usersLabel).isEqualTo("2")
        assertThat(card.activeUsers).isEqualTo(1)
        assertThat(card.endDate).isEqualTo("2026-09-27")
    }
}
