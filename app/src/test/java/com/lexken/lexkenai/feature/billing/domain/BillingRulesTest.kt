package com.lexken.lexkenai.feature.billing.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.model.UserRole
import org.junit.jupiter.api.Test

/** T1 for F2: appendix auth rules 28–33 and chat rule 28, one test per clause. */
class BillingRulesTest {
    private fun subscription(id: String? = "sub-1", active: Boolean = true, paid: Boolean = true, endDate: String? = "2026-11-12") =
        Subscription(
            id,
            "Solo",
            "SOLO",
            isActive = active,
            isPaid = paid,
            isExpired = false,
            startDate = "2026-10-12",
            endDate = endDate,
            creditsResetDate = null,
            creditsBalance = 120,
        )

    @Test
    fun `r31 the provider is paystack unless the config says daraja`() {
        assertThat(PaymentProviderRules.resolve("daraja")).isEqualTo(PaymentProvider.DARAJA)
        assertThat(PaymentProviderRules.resolve(" Daraja ")).isEqualTo(PaymentProvider.DARAJA)
        assertThat(PaymentProviderRules.resolve("paystack")).isEqualTo(PaymentProvider.PAYSTACK)
        assertThat(PaymentProviderRules.resolve("mpesa")).isEqualTo(PaymentProvider.PAYSTACK)
        assertThat(PaymentProviderRules.resolve(null)).isEqualTo(PaymentProvider.PAYSTACK)
    }

    @Test
    fun `r30 a top-up is two credits per shilling, 100 to 750 in steps of ten, phone only for daraja`() {
        assertThat(CreditsTopUpRules.creditsFor(500)).isEqualTo(1_000)
        assertThat(CreditsTopUpRules.validateAmount(100)).isNull()
        assertThat(CreditsTopUpRules.validateAmount(750)).isNull()
        assertThat(CreditsTopUpRules.validateAmount(90)).isEqualTo(PurchaseProblem.AMOUNT_BELOW_MINIMUM)
        assertThat(CreditsTopUpRules.validateAmount(760)).isEqualTo(PurchaseProblem.AMOUNT_ABOVE_MAXIMUM)
        assertThat(CreditsTopUpRules.validateAmount(505)).isEqualTo(PurchaseProblem.AMOUNT_NOT_A_STEP)
        assertThat(CreditsTopUpRules.validate(CreditsPurchase(500, PaymentProvider.PAYSTACK), subscriptionActive = true)).isNull()
        assertThat(
            CreditsTopUpRules.validate(CreditsPurchase(500, PaymentProvider.DARAJA), subscriptionActive = true),
        ).isEqualTo(PurchaseProblem.PHONE_REQUIRED)
        assertThat(CreditsTopUpRules.validate(CreditsPurchase(500, PaymentProvider.DARAJA, "0722334455"), subscriptionActive = true)).isNull()
        assertThat(
            CreditsTopUpRules.validate(CreditsPurchase(500, PaymentProvider.PAYSTACK), subscriptionActive = false),
        ).isEqualTo(PurchaseProblem.NO_ACTIVE_SUBSCRIPTION)
    }

    @Test
    fun `r30 the daraja phone is 07 or 01 followed by eight digits`() {
        assertThat(DarajaPhoneRules.isValid("0722334455")).isTrue()
        assertThat(DarajaPhoneRules.isValid("0112233445")).isTrue()
        assertThat(DarajaPhoneRules.isValid("+254722334455")).isFalse()
        assertThat(DarajaPhoneRules.isValid("254722334455")).isFalse()
        assertThat(DarajaPhoneRules.isValid("072233445")).isFalse()
        assertThat(DarajaPhoneRules.validate("")).isEqualTo(PurchaseProblem.PHONE_REQUIRED)
        assertThat(DarajaPhoneRules.validate("0822334455")).isEqualTo(PurchaseProblem.PHONE_INVALID)
    }

    @Test
    fun `r29 the badge is active when paid, pending when unpaid, inactive otherwise, none without an id`() {
        assertThat(SubscriptionBadgeRules.badge(subscription())).isEqualTo(SubscriptionBadge.ACTIVE)
        assertThat(SubscriptionBadgeRules.badge(subscription(paid = false))).isEqualTo(SubscriptionBadge.PENDING_PAYMENT)
        assertThat(SubscriptionBadgeRules.badge(subscription(active = false))).isEqualTo(SubscriptionBadge.INACTIVE)
        assertThat(SubscriptionBadgeRules.badge(subscription(id = null))).isEqualTo(SubscriptionBadge.NONE)
        assertThat(SubscriptionBadgeRules.badge(null)).isEqualTo(SubscriptionBadge.NONE)
    }

    @Test
    fun `r29 a top-up needs an active subscription whose end date has not passed, or an active assignment`() {
        assertThat(SubscriptionBadgeRules.allowsTopUp(subscription(endDate = "2026-10-13"), today = "2026-10-13")).isTrue()
        assertThat(SubscriptionBadgeRules.allowsTopUp(subscription(endDate = "2026-10-12"), today = "2026-10-13")).isFalse()
        assertThat(SubscriptionBadgeRules.allowsTopUp(subscription(active = false), today = "2026-10-13")).isFalse()
        assertThat(SubscriptionBadgeRules.allowsTopUp(subscription().copy(assigned = true, assignmentActive = true), today = "2026-10-13")).isTrue()
        assertThat(SubscriptionBadgeRules.allowsTopUp(subscription().copy(assigned = true, assignmentActive = false), today = "2026-10-13")).isFalse()
        assertThat(SubscriptionBadgeRules.allowsTopUp(null, today = "2026-10-13")).isFalse()
    }

    @Test
    fun `r30 usage estimates divide the balance by 10, 25 and 4 and drop zero rows`() {
        assertThat(UsageEstimateRules.estimates(120)).containsExactly(
            UsageEstimate(UsageEstimate.Kind.PRECEDENT_RETRIEVALS, 12),
            UsageEstimate(UsageEstimate.Kind.CASE_BRIEFS, 4),
            UsageEstimate(UsageEstimate.Kind.TEMPLATE_DOCUMENTS, 30),
        )
        assertThat(UsageEstimateRules.estimates(9)).containsExactly(UsageEstimate(UsageEstimate.Kind.TEMPLATE_DOCUMENTS, 2))
        assertThat(UsageEstimateRules.estimates(0)).isEmpty()
        assertThat(UsageEstimateRules.estimates(null)).isEmpty()
    }

    @Test
    fun `r32 the catalogue keeps PUB, SOLO and BSC in order, labels a 30-day plan a month, and closes with Enterprise`() {
        val plans = listOf(
            Plan("p-bsc", "BSC", "Basic", 5000.0, 90, 15),
            Plan("p-pro", "PRO", "Pro", 9000.0, 30, 25),
            Plan("p-solo", "SOLO", "Solo", 2500.0, 30, 2),
            Plan("p-pub", "pub", "Non-Litigant", 1500.0, 30, 1),
        )
        val catalogue = PlanCatalogRules.build(plans, currentTier = "SOLO")
        assertThat(catalogue.map { it.tier }).containsExactly("PUB", "SOLO", "BSC", "ENT")
        assertThat(catalogue[0].durationDisplay).isEqualTo(DurationDisplay.MONTH)
        assertThat(catalogue[2].durationDisplay).isEqualTo(DurationDisplay.BILLING_PERIOD)
        assertThat(catalogue[1].isCurrent).isTrue()
        assertThat(catalogue.last().contactOnly).isTrue()
        assertThat(catalogue.last().priceKes).isNull()
        assertThat(catalogue.last().durationDisplay).isEqualTo(DurationDisplay.TAILORED)
        assertThat(catalogue[1].name).isEqualTo("Solo")
        assertThat(PlanCatalogRules.EXPECTED_PRICES_KES["BSC"]).isEqualTo(5000.0)
    }

    @Test
    fun `r28 a public user cannot buy a firm plan without the web's elevation`() {
        val catalogue = PlanCatalogRules.build(listOf(Plan("p-solo", "SOLO", "Solo", 2500.0, 30, 2), Plan("p-pub", "PUB", "Non-Litigant", 1500.0, 30, 1)))
        assertThat(PlanAudienceRules.needsElevation(UserRole.PUBLIC, "BSC")).isTrue()
        assertThat(PlanAudienceRules.needsElevation(UserRole.ADVOCATE, "BSC")).isFalse()
        assertThat(PlanAudienceRules.purchasable(catalogue, UserRole.PUBLIC).map { it.tier }).containsExactly("PUB", "ENT")
        assertThat(PlanAudienceRules.purchasable(catalogue, UserRole.ADMIN).map { it.tier }).containsExactly("PUB", "SOLO", "ENT")
    }

    @Test
    fun `polling terminal map settles SUCCESS and FAILED on both rails, CANCELLED on daraja only`() {
        assertThat(PaymentOutcomeRules.terminal("SUCCESS", PaymentProvider.PAYSTACK)).isEqualTo(PaymentOutcome.SUCCESS)
        assertThat(PaymentOutcomeRules.terminal("completed", PaymentProvider.DARAJA)).isEqualTo(PaymentOutcome.SUCCESS)
        assertThat(PaymentOutcomeRules.terminal("FAILED", PaymentProvider.DARAJA)).isEqualTo(PaymentOutcome.FAILED)
        assertThat(PaymentOutcomeRules.terminal("CANCELLED", PaymentProvider.DARAJA)).isEqualTo(PaymentOutcome.CANCELLED)
        assertThat(PaymentOutcomeRules.terminal("CANCELLED", PaymentProvider.PAYSTACK)).isNull()
        assertThat(PaymentOutcomeRules.terminal("PENDING", PaymentProvider.PAYSTACK)).isNull()
        assertThat(PaymentOutcomeRules.terminal(null, PaymentProvider.PAYSTACK)).isNull()
        assertThat(PaymentOutcomeRules.notFoundIsFailure(PaymentProvider.DARAJA)).isTrue()
        assertThat(PaymentOutcomeRules.notFoundIsFailure(PaymentProvider.PAYSTACK)).isFalse()
    }

    @Test
    fun `r31 the word paystack is rewritten to Pay in any case`() {
        assertThat(
            PaymentMessageRules.clientMessage("Paystack checkout is unavailable. Try PAYSTACK later."),
        ).isEqualTo("Pay checkout is unavailable. Try Pay later.")
        assertThat(PaymentMessageRules.clientMessage("  ")).isNull()
        assertThat(PaymentMessageRules.clientMessage(null)).isNull()
    }

    @Test
    fun `r33 the payment flow clears the discount when the code changes and returns to plan only with a payment id`() {
        val solo = PlanCatalogRules.build(listOf(Plan("p-solo", "SOLO", "Solo", 2500.0, 30, 2)))[0]
        val discount = Discount("LEX50", 2500.0, 1250.0, 50.0, 1250.0)
        var flow = PaymentFlow().choosePlan(solo)
        assertThat(flow.step).isEqualTo(PaymentFlow.Step.DETAILS)
        assertThat(flow.canReturnToPlan).isFalse()
        assertThat(flow.returnToPlan()).isEqualTo(flow)
        flow = flow.typeCode("LEX50").applyDiscount(discount)
        assertThat(flow.priceDue).isEqualTo(1250.0)
        flow = flow.typeCode("LEX5")
        assertThat(flow.discount).isNull()
        assertThat(flow.priceDue).isEqualTo(2500.0)
        flow = flow.typeCode("LEX5")
        assertThat(flow.discount).isNull()
        flow = flow.initiated("pay-1")
        assertThat(flow.step).isEqualTo(PaymentFlow.Step.PROCESSING)
        flow = flow.settled(PaymentOutcome.TIMED_OUT)
        assertThat(flow.step).isEqualTo(PaymentFlow.Step.OUTCOME)
        assertThat(flow.canReturnToPlan).isTrue()
        assertThat(flow.returnToPlan().step).isEqualTo(PaymentFlow.Step.DETAILS)
        assertThat(PaymentFlow().choosePlan(solo).initiationFailed().step).isEqualTo(PaymentFlow.Step.DETAILS)
    }

    @Test
    fun `r7 the credits wall reads detail on a 403 body and data on a socket frame, falling back to the top level`() {
        val http = CreditsWallRules.fromHttpBody(
            """{"error_code":"INSUFFICIENT_AI_CREDITS","message":"Insufficient AI credits.","detail":{"personal_balance":4,"required_credits":10}}""",
        )
        assertThat(http).isEqualTo(CreditsWall("Insufficient AI credits.", currentBalance = 4, requiredCredits = 10))
        val socket = CreditsWallRules.fromSocketFrame(
            """{"type":"subscription.error","message":"Out of credits","data":{"personal_balance":0,"required_credits":25}}""",
        )
        assertThat(socket).isEqualTo(CreditsWall("Out of credits", 0, 25))
        val flat = CreditsWallRules.fromHttpBody("""{"message":"Insufficient","personal_balance":2}""")
        assertThat(flat).isEqualTo(CreditsWall("Insufficient", 2, null))
        assertThat(CreditsWallRules.fromHttpBody("<html>")).isNull()
    }

    @Test
    fun `credits history labels features, flags refunds, and makes the next link relative to the api base`() {
        assertThat(CreditHistoryRules.featureLabel("RAG")).isEqualTo("Internal DB Precedent Retrieval")
        assertThat(CreditHistoryRules.featureLabel("AI_REQUEST_REFUND")).isEqualTo("Credit Refund")
        assertThat(CreditHistoryRules.featureLabel("CONTRACT_ANALYSIS")).isEqualTo("Contract Analysis")
        assertThat(CreditHistoryRules.featureLabel(null)).isEqualTo("Unknown")
        assertThat(CreditHistoryRules.isRefund("charge", false, -5)).isTrue()
        assertThat(CreditHistoryRules.isRefund("refund", null, 10)).isTrue()
        assertThat(CreditHistoryRules.isRefund("charge", false, 10)).isFalse()
        assertThat(CreditHistoryRules.relativePage("http://lawris-web/ai/ai-credits-history/?page=2")).isEqualTo("ai/ai-credits-history/?page=2")
        assertThat(CreditHistoryRules.relativePage("https://stage.lexken.ai/api/ai/ai-credits-history/?page=3")).isEqualTo("ai/ai-credits-history/?page=3")
        assertThat(CreditHistoryRules.relativePage("/ai/ai-credits-history/")).isEqualTo("ai/ai-credits-history/")
        assertThat(CreditHistoryRules.relativePage(null)).isNull()
    }

    @Test
    fun `payment history re-reads every five seconds only while a fresh successful payment lacks its invoice`() {
        val paid = Payment("p-1", null, null, "Solo plan", "Solo", "QGH7K2L9MN", 2500.0, PaymentStatus.SUCCESS, "SUCCESS", invoice = null)
        assertThat(PaymentHistoryRules.shouldRefresh(listOf(paid)) { 30_000L }).isTrue()
        assertThat(PaymentHistoryRules.shouldRefresh(listOf(paid)) { 3 * 60_000L }).isFalse()
        assertThat(PaymentHistoryRules.shouldRefresh(listOf(paid.copy(invoice = Invoice("i-1", "INV-1", null)))) { 1_000L }).isFalse()
        assertThat(PaymentHistoryRules.shouldRefresh(listOf(paid.copy(status = PaymentStatus.PENDING))) { 1_000L }).isFalse()
        assertThat(PaymentHistoryRules.status("cancelled")).isEqualTo(PaymentStatus.CANCELLED)
        assertThat(PaymentHistoryRules.status("REFUNDED")).isEqualTo(PaymentStatus.OTHER)
        assertThat(paid.copy(invoice = Invoice("i-1", "INV-1", null)).invoiceAvailable).isTrue()
    }

    @Test
    fun `r34 the expired notice is consumed once per sign-in and re-armed only after a clear`() = kotlinx.coroutines.test.runTest {
        val notice = ExpiredSubscriptionNotice()
        assertThat(notice.consume()).isNull()
        notice.arm(ExpiredSubscriptionInfo("Solo", "2026-10-01", 0))
        assertThat(notice.consume()).isEqualTo(ExpiredSubscriptionInfo("Solo", "2026-10-01", 0))
        assertThat(notice.consume()).isNull()
        notice.arm(ExpiredSubscriptionInfo("Solo", "2026-10-01", 0))
        assertThat(notice.consume()).isNull()
        notice.clear()
        notice.arm(ExpiredSubscriptionInfo("Solo", "2026-10-01", 0))
        assertThat(notice.consume()).isEqualTo(ExpiredSubscriptionInfo("Solo", "2026-10-01", 0))
    }
}
