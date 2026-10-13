package com.lexken.lexkenai.feature.billing.domain

import com.lexken.lexkenai.core.model.UserRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** `paymentService.jsx:4-13` (rule 31): anything other than `daraja` is Paystack. */
object PaymentProviderRules {
    fun resolve(configured: String?): PaymentProvider =
        if (configured?.trim().equals("daraja", ignoreCase = true)) PaymentProvider.DARAJA else PaymentProvider.PAYSTACK
}

/** `CreditsModal.jsx:26-31,164-180` (rule 30/28): 2 credits per KES, KES 100–750 in steps of 10, phone only for Daraja. */
object CreditsTopUpRules {
    const val CREDITS_PER_KES = 2
    const val MIN_KES = 100
    const val MAX_KES = 750
    const val STEP_KES = 10

    fun creditsFor(kes: Int): Int = Math.floorDiv(kes * CREDITS_PER_KES, 1)

    /** Above the maximum the web says "subscribe instead" rather than clamp. */
    fun validateAmount(kes: Int): PurchaseProblem? = when {
        kes < MIN_KES -> PurchaseProblem.AMOUNT_BELOW_MINIMUM
        kes > MAX_KES -> PurchaseProblem.AMOUNT_ABOVE_MAXIMUM
        kes % STEP_KES != 0 -> PurchaseProblem.AMOUNT_NOT_A_STEP
        else -> null
    }

    fun validate(purchase: CreditsPurchase, subscriptionActive: Boolean): PurchaseProblem? {
        if (!subscriptionActive) return PurchaseProblem.NO_ACTIVE_SUBSCRIPTION
        validateAmount(purchase.amountKes)?.let { return it }
        return if (purchase.provider == PaymentProvider.DARAJA) DarajaPhoneRules.validate(purchase.phoneNumber) else null
    }
}

/** `^(07|01)\d{8}$` (rule 30): a Kenyan mobile number as the user types it, no country code. */
object DarajaPhoneRules {
    private val PATTERN = Regex("^(07|01)\\d{8}$")

    fun isValid(phone: String?): Boolean = phone != null && PATTERN.matches(phone.trim())

    fun validate(phone: String?): PurchaseProblem? = when {
        phone.isNullOrBlank() -> PurchaseProblem.PHONE_REQUIRED
        !isValid(phone) -> PurchaseProblem.PHONE_INVALID
        else -> null
    }
}

/** Rule 29. */
object SubscriptionBadgeRules {
    fun badge(subscription: Subscription?): SubscriptionBadge = when {
        subscription == null || subscription.id.isNullOrBlank() -> SubscriptionBadge.NONE
        subscription.isActive && subscription.isPaid -> SubscriptionBadge.ACTIVE
        subscription.isActive -> SubscriptionBadge.PENDING_PAYMENT
        else -> SubscriptionBadge.INACTIVE
    }

    /**
     * `CreditsModal.jsx:74-111`: a top-up is allowed when the own subscription is active and
     * its `end_date` is today or later, or the employee's assignment is active. [today] is an
     * ISO date (`YYYY-MM-DD`); the server's dates compare lexically.
     */
    fun allowsTopUp(subscription: Subscription?, today: String): Boolean {
        if (subscription == null) return false
        if (subscription.assigned) return subscription.assignmentActive == true
        val endDate = subscription.endDate ?: return subscription.isActive
        return subscription.isActive && endDate >= today
    }
}

/** `UserBilling.jsx:17-21,253-284` (rule 30): only when the subscription carries a balance. */
object UsageEstimateRules {
    const val PRECEDENT_RETRIEVAL = 10
    const val CASE_BRIEF = 25
    const val TEMPLATE_DOCUMENT = 4

    fun estimates(creditsBalance: Int?): List<UsageEstimate> {
        val balance = creditsBalance?.takeIf { it > 0 } ?: return emptyList()
        return listOf(
            UsageEstimate(UsageEstimate.Kind.PRECEDENT_RETRIEVALS, balance / PRECEDENT_RETRIEVAL),
            UsageEstimate(UsageEstimate.Kind.CASE_BRIEFS, balance / CASE_BRIEF),
            UsageEstimate(UsageEstimate.Kind.TEMPLATE_DOCUMENTS, balance / TEMPLATE_DOCUMENT),
        ).filter { it.count > 0 }
    }
}

/** `UserBilling.jsx:28-71` and `LexkenPaymentHistory.jsx`: status mapping and the invoice re-read window. */
object PaymentHistoryRules {
    const val REFRESH_MILLIS = 5_000L
    const val INVOICE_WAIT_MILLIS = 2 * 60_000L

    fun status(raw: String?): PaymentStatus = when (raw?.trim()?.uppercase()) {
        "SUCCESS" -> PaymentStatus.SUCCESS
        "PENDING" -> PaymentStatus.PENDING
        "FAILED" -> PaymentStatus.FAILED
        "CANCELLED" -> PaymentStatus.CANCELLED
        else -> PaymentStatus.OTHER
    }

    /** The web polls every 5 s while a SUCCESS payment has no invoice yet and was updated under two minutes ago. */
    fun shouldRefresh(payments: List<Payment>, updatedAgoMillis: (Payment) -> Long?): Boolean =
        payments.any { payment ->
            payment.status == PaymentStatus.SUCCESS && payment.invoice == null &&
                (updatedAgoMillis(payment) ?: Long.MAX_VALUE) < INVOICE_WAIT_MILLIS
        }
}

/** `getTerminalPaymentOutcome` (polling table): SUCCESS and FAILED settle on both rails; CANCELLED only on Daraja. */
object PaymentOutcomeRules {
    const val POLL_MILLIS = 5_000L
    const val TIMEOUT_MILLIS = 90_000L

    fun terminal(rawStatus: String?, provider: PaymentProvider): PaymentOutcome? =
        when (rawStatus?.trim()?.uppercase()) {
            "SUCCESS", "COMPLETED" -> PaymentOutcome.SUCCESS
            "FAILED" -> PaymentOutcome.FAILED
            "CANCELLED" -> if (provider == PaymentProvider.DARAJA) PaymentOutcome.CANCELLED else null
            else -> null
        }

    /** A 404 from the status route ends a Daraja poll as failed; Paystack keeps polling (polling table, auth #51). */
    fun notFoundIsFailure(provider: PaymentProvider): Boolean = provider == PaymentProvider.DARAJA
}

/** `paymentService.jsx:29-32` (rule 31): the user never reads the word "Paystack". */
object PaymentMessageRules {
    private val PAYSTACK = Regex("paystack", RegexOption.IGNORE_CASE)

    fun clientMessage(message: String?): String? = message?.takeIf { it.isNotBlank() }?.replace(PAYSTACK, "Pay")
}

/** `buildLexkenPlanCatalog` (rule 32): PUB / SOLO / BSC merged with the static copy, Enterprise appended. */
object PlanCatalogRules {
    const val ENTERPRISE_ID = "lexken-enterprise"
    const val ENTERPRISE_TIER = "ENT"
    const val ENTERPRISE_MAILTO = "mailto:support@lexken.ai?subject=Lexken%20Enterprise"
    const val MONTH_DAYS = 30

    /** The web's expected prices (its test helper); the server's price always wins. */
    val EXPECTED_PRICES_KES = mapOf("PUB" to 1500.0, "SOLO" to 2500.0, "BSC" to 5000.0)

    private const val BRS_INFO = "An autonomous agent that works with the Business Registration Service to prepare company registrations and amendments."
    private const val CTS_INFO = "An autonomous filing agent that works with the court e-filing platform to submit matters on your behalf."

    data class Presentation(val order: Int, val name: String, val audience: String, val summary: String, val features: List<PlanFeature>)

    val presentations: Map<String, Presentation> = mapOf(
        "PUB" to Presentation(
            order = 1,
            name = "Non-Litigant",
            audience = "For focused legal and advisory work",
            summary = "The essential Lexken workspace for research, drafting, and documents.",
            features = listOf(
                PlanFeature("Lexken AI"),
                PlanFeature("Document management"),
                PlanFeature("File Manager with 500 MB storage"),
                PlanFeature("1 user seat"),
            ),
        ),
        "SOLO" to Presentation(
            order = 2,
            name = "Solo",
            audience = "For solo practitioners and compact firms",
            summary = "Bring your firm's own work into a private, searchable AI workspace.",
            features = listOf(
                PlanFeature("Everything in Non-Litigant"),
                PlanFeature("2 GB Firm Knowledge Store", "Upload your firm's precedents, templates and opinions for private retrieval."),
                PlanFeature("2 user seats"),
                PlanFeature("Limited BRS Agent access", BRS_INFO),
                PlanFeature("Limited CTS Agent access", CTS_INFO),
            ),
        ),
        "BSC" to Presentation(
            order = 3,
            name = "Basic",
            audience = "For mid-sized law firms",
            summary = "Advanced analysis, playbooks, and agents for repeatable firm-wide work.",
            features = listOf(
                PlanFeature("Everything in Solo"),
                PlanFeature("Advanced contract analysis"),
                PlanFeature("Predefined legal playbooks"),
                PlanFeature("10 GB Firm Knowledge Store"),
                PlanFeature("Up to 15 user seats"),
                PlanFeature("BRS Agent", BRS_INFO),
                PlanFeature("CTS Agent", CTS_INFO),
            ),
        ),
    )

    val enterprise = CatalogPlan(
        id = ENTERPRISE_ID,
        tier = ENTERPRISE_TIER,
        name = "Enterprise",
        audience = "For large firms and specialist legal teams",
        summary = "A tailored Lexken environment built around your data, systems, and operating model.",
        priceKes = null,
        durationDisplay = DurationDisplay.TAILORED,
        features = listOf(
            PlanFeature("Model training on approved custom datasets"),
            PlanFeature("Advanced vector storage and retrieval"),
            PlanFeature("Knowledge graph for deeper matter traversal"),
            PlanFeature("Custom agents, workflows, and integrations"),
            PlanFeature("Tailored seats, storage, security, and onboarding"),
        ),
        contactOnly = true,
    )

    fun durationDisplay(days: Int?): DurationDisplay = if (days == MONTH_DAYS) DurationDisplay.MONTH else DurationDisplay.BILLING_PERIOD

    fun build(plans: List<Plan>, currentTier: String? = null): List<CatalogPlan> {
        val purchasable = plans.mapNotNull { plan ->
            val p = presentations[plan.tier.uppercase()] ?: return@mapNotNull null
            p.order to CatalogPlan(
                id = plan.id,
                tier = plan.tier.uppercase(),
                name = p.name,
                audience = p.audience,
                summary = p.summary,
                priceKes = plan.priceKes,
                durationDisplay = durationDisplay(plan.durationDays),
                features = p.features,
                isCurrent = plan.tier.equals(currentTier, ignoreCase = true),
            )
        }.sortedBy { it.first }.map { it.second }
        return purchasable + enterprise.copy(isCurrent = ENTERPRISE_TIER.equals(currentTier, ignoreCase = true))
    }

    /** Rule 33: the price shown is the discounted one once a code is applied. */
    fun priceDue(plan: CatalogPlan, discount: Discount?): Double? = discount?.discountedPriceKes ?: plan.priceKes
}

/**
 * `PaymentProcessing.jsx:154-171` (rule 28): a `public` user choosing SOLO or BSC must be
 * elevated (firm name + practising number verified over `/ws/verification/`). That rail is
 * web-only (auth #58, parked), so mobile points those users at Enterprise / contact instead.
 */
object PlanAudienceRules {
    val FIRM_TIERS: Set<String> = setOf("SOLO", "BSC")

    fun needsElevation(role: UserRole, tier: String): Boolean = role == UserRole.PUBLIC && tier.uppercase() in FIRM_TIERS

    fun purchasable(plans: List<CatalogPlan>, role: UserRole): List<CatalogPlan> =
        plans.filterNot { needsElevation(role, it.tier) }
}

/** `AICreditsHistory.jsx` and `creditService.jsx:57-66`. */
object CreditHistoryRules {
    /** `type === 'refund' || is_refund || amount < 0`. */
    fun isRefund(type: String?, isRefund: Boolean?, amount: Int): Boolean =
        type.equals("refund", ignoreCase = true) || isRefund == true || amount < 0

    /** Feature codes read as the web writes them; the rest go snake_case → Title Case. */
    fun featureLabel(code: String?): String = when (code?.uppercase()) {
        null, "" -> "Unknown"
        "RAG" -> "Internal DB Precedent Retrieval"
        "AI_REQUEST_REFUND" -> "Credit Refund"
        else -> code.lowercase().split('_').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    /**
     * DRF's `next` is absolute and built from the request host, which behind nginx is the bare
     * Django host without `/api`. The web follows it as a relative path; so does mobile:
     * `http://host/ai/ai-credits-history/?page=2` → `ai/ai-credits-history/?page=2`.
     */
    fun relativePage(link: String?): String? {
        val raw = link?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutScheme = raw.substringAfter("://", raw)
        val path = if (withoutScheme == raw) raw else withoutScheme.substringAfter('/', "")
        return path.removePrefix("/").removePrefix("api/").ifEmpty { null }
    }
}

/**
 * Rule 7 (403 bodies) and the `credits.insufficient` / `subscription.error` frames: the
 * balance lives under `detail` on the view's 403 and under `data` on the socket frame; the web
 * reads both and falls back to the top level.
 */
object CreditsWallRules {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun fromHttpBody(body: String?): CreditsWall? = parse(body, nested = "detail")

    fun fromSocketFrame(frame: String?): CreditsWall? = parse(frame, nested = "data")

    private fun parse(text: String?, nested: String): CreditsWall? {
        val obj = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        val inner = obj[nested] as? JsonObject
        val balance = inner?.int("personal_balance") ?: obj.int("personal_balance") ?: 0
        val required = inner?.int("required_credits") ?: obj.int("required_credits")
        val message = obj.string("message") ?: obj.string("detail")
        return CreditsWall(message = message, currentBalance = balance, requiredCredits = required)
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.intOrNull

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
}

/**
 * `PaymentProcessing.jsx:154-272` (rule 33): plans → details → processing → outcome. The
 * applied discount is dropped when the code input changes; "return to plan" exists only once a
 * `payment_id` does. Pure state, no I/O — the use cases drive it.
 */
data class PaymentFlow(
    val step: Step = Step.PLANS,
    val plan: CatalogPlan? = null,
    val codeInput: String = "",
    val discount: Discount? = null,
    val paymentId: String? = null,
    val outcome: PaymentOutcome? = null,
) {
    enum class Step { PLANS, DETAILS, PROCESSING, OUTCOME }

    val priceDue: Double? get() = plan?.let { PlanCatalogRules.priceDue(it, discount) }
    val canReturnToPlan: Boolean get() = paymentId != null

    fun choosePlan(plan: CatalogPlan): PaymentFlow = copy(step = Step.DETAILS, plan = plan, codeInput = "", discount = null)

    fun typeCode(code: String): PaymentFlow = if (code == codeInput) this else copy(codeInput = code, discount = null)

    fun applyDiscount(discount: Discount): PaymentFlow = copy(discount = discount)

    fun initiated(paymentId: String): PaymentFlow = copy(step = Step.PROCESSING, paymentId = paymentId, outcome = null)

    fun settled(outcome: PaymentOutcome): PaymentFlow = copy(step = Step.OUTCOME, outcome = outcome)

    /** A failed initiation returns to the details step (auth #48). */
    fun initiationFailed(): PaymentFlow = copy(step = Step.DETAILS)

    fun returnToPlan(): PaymentFlow = if (canReturnToPlan) copy(step = Step.DETAILS, outcome = null) else this
}
