package com.lexken.lexkenai.feature.billing.domain

import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Error

// ── Rails (web `paymentService.jsx`) ─────────────────────────────────────────

/** The two payment rails. The build config picks one (rule 31): Paystack unless it says `daraja`. */
enum class PaymentProvider { PAYSTACK, DARAJA }

/** What a poll of the payment's status settled on (rule 33, step 4). */
enum class PaymentOutcome { SUCCESS, FAILED, CANCELLED, TIMED_OUT }

// ── Subscription (web `UserBilling.jsx`, `CreditsModal.jsx`) ────────────────

/** `GET sub/current/` (auth #42): the user's own subscription, or an employee's assigned one. */
data class Subscription(
    val id: String?,
    val planName: String?,
    val tier: String?,
    val isActive: Boolean,
    val isPaid: Boolean,
    val isExpired: Boolean,
    /** ISO dates as the server sends them (`YYYY-MM-DD`). */
    val startDate: String?,
    val endDate: String?,
    val creditsResetDate: String?,
    val creditsBalance: Int?,
    /** True when this came from `assigned_subscription` (an employee of a firm). */
    val assigned: Boolean = false,
    /** `assigned_subscription.is_active` — the employee's own assignment flag. */
    val assignmentActive: Boolean? = null,
)

/** `UserBilling.jsx:331-336` (rule 29). */
enum class SubscriptionBadge { ACTIVE, PENDING_PAYMENT, INACTIVE, NONE }

/** What the top bar shows (auth #43, rule 30). */
data class CreditsBalance(
    val credits: Int,
    /** `ai_credits_reset_date`; the web falls back to a fixed date, mobile keeps null. */
    val resetsOn: String?,
    val personal: Int,
    val subscription: Int?,
    val sharedMonthlyLimit: Int?,
)

/** `UserBilling.jsx:17-21` (rule 30): what the balance roughly buys, zero rows dropped. */
data class UsageEstimate(val kind: Kind, val count: Int) {
    enum class Kind { PRECEDENT_RETRIEVALS, CASE_BRIEFS, TEMPLATE_DOCUMENTS }
}

// ── Payments and invoices (auth #44, #45) ───────────────────────────────────

enum class PaymentStatus { SUCCESS, PENDING, FAILED, CANCELLED, OTHER }

data class Invoice(val id: String, val number: String?, val pdfUrl: String?)

data class Payment(
    val id: String,
    val createdAt: String?,
    val updatedAt: String?,
    val description: String?,
    val planName: String?,
    val receipt: String?,
    /** Money arrives as a string ("2500.00"). */
    val amountKes: Double?,
    val status: PaymentStatus,
    val rawStatus: String?,
    val invoice: Invoice?,
) {
    /** The web enables View / Download only for a successful payment that has an invoice. */
    val invoiceAvailable: Boolean get() = invoice != null && status == PaymentStatus.SUCCESS
}

sealed interface InvoiceError : Error {
    /** 404: the PDF is not rendered yet. */
    data object NotReady : InvoiceError

    data object NoLink : InvoiceError

    data class Transport(val error: DataError) : InvoiceError
}

// ── Catalogue (auth #46, rule 32, `config/lexkenSubscriptionPlans.js`) ──────

/** One row of `GET sub/plans/` as the server sends it. */
data class Plan(
    val id: String,
    val tier: String,
    val name: String,
    val priceKes: Double?,
    val durationDays: Int?,
    val usersIncluded: Int?,
)

enum class DurationDisplay { MONTH, BILLING_PERIOD, TAILORED }

data class PlanFeature(val label: String, val info: String? = null)

/** A purchasable plan as the page renders it, or the contact-only Enterprise row. */
data class CatalogPlan(
    val id: String,
    val tier: String,
    val name: String,
    val audience: String,
    val summary: String,
    /** Null for the contact-only plan. */
    val priceKes: Double?,
    val durationDisplay: DurationDisplay,
    val features: List<PlanFeature>,
    val contactOnly: Boolean = false,
    val isCurrent: Boolean = false,
)

// ── Discounts (auth #47, #56) ───────────────────────────────────────────────

data class Discount(
    val code: String,
    val originalPriceKes: Double?,
    val discountedPriceKes: Double,
    val percentage: Double?,
    val amountKes: Double?,
)

sealed interface DiscountError : Error {
    /** The web shows one fixed sentence for any error — the server's reason is deliberately not surfaced. */
    data object Invalid : DiscountError

    data class Transport(val error: DataError) : DiscountError
}

/** `POST sub/request_discount_code` → 201 `{discount_code, valid_days}`. */
data class DiscountGrant(val code: String, val validDays: Int?)

// ── Purchase (auth #48–#53, rule 33) ────────────────────────────────────────

/** The web's `/pay` elevation block for a `public` user choosing a firm plan (rule 28). */
data class ElevationDetails(val lawFirmName: String, val registrationNumber: String)

/** What the checkout needs to start a subscription payment. */
data class SubscriptionPurchase(
    val planId: String,
    val provider: PaymentProvider,
    val discountCode: String? = null,
    /** `^(07|01)\d{8}$`, Daraja only (rule 30). */
    val phoneNumber: String? = null,
    val elevation: ElevationDetails? = null,
)

/** A credit top-up (rule 28): KES in [100, 750], phone for Daraja only. */
data class CreditsPurchase(
    val amountKes: Int,
    val provider: PaymentProvider,
    val phoneNumber: String? = null,
)

/** The server accepted the payment request; the client either opens a checkout or waits for the STK push. */
sealed interface PaymentInitiated {
    val paymentId: String

    /** Paystack: open [authorizationUrl] in a Custom Tab (no PaystackPop on mobile), then poll. */
    data class Checkout(override val paymentId: String, val authorizationUrl: String, val accessCode: String?, val publicKey: String?) : PaymentInitiated

    /** Daraja: the STK push went out; [message] is the server's sentence. */
    data class PushSent(override val paymentId: String, val message: String?) : PaymentInitiated
}

sealed interface PaymentError : Error {
    data class Invalid(val problem: PurchaseProblem) : PaymentError

    /** The server's own sentence, "Paystack" already rewritten to "Pay" (rule 31). */
    data class Rejected(val message: String) : PaymentError

    /** Credit top-ups need an active subscription (`SUBSCRIPTION_REQUIRED`). */
    data object SubscriptionRequired : PaymentError

    data class Transport(val error: DataError) : PaymentError
}

enum class PurchaseProblem : Error {
    PHONE_REQUIRED,
    PHONE_INVALID,
    AMOUNT_BELOW_MINIMUM,
    AMOUNT_ABOVE_MAXIMUM,
    AMOUNT_NOT_A_STEP,
    ELEVATION_REQUIRED,
    NO_ACTIVE_SUBSCRIPTION,
}

/** One read of the status route while polling (auth #50/#51). */
data class PaymentStatusRead(val rawStatus: String?, val receipt: String?, val reference: String?)

// ── Credits history (auth #54) ──────────────────────────────────────────────

data class CreditEntry(
    val id: String,
    val timestamp: String?,
    val feature: String,
    val featureLabel: String,
    val amount: Int,
    val isRefund: Boolean,
    val details: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
)

/** [nextPage] / [previousPage] are relative paths ready for the API base (rule: the web follows `next` as a relative path). */
data class CreditHistoryPage(
    val entries: List<CreditEntry>,
    val count: Int?,
    val nextPage: String?,
    val previousPage: String?,
)

// ── Plan card (auth #57) ────────────────────────────────────────────────────

/** `GET auth/api/my-plan-card/`: what the file manager's storage card reads. */
data class PlanCard(
    val tier: String?,
    val planName: String?,
    val storageLabel: String?,
    val usersLabel: String?,
    val activeUsers: Int?,
    val storageUsedBytes: Long?,
    val endDate: String?,
)

// ── The credits wall (rule 7, 403 bodies and `credits.insufficient` frames) ─

/** What opens the top-up sheet: the server's sentence plus the balance it reported. */
data class CreditsWall(
    val message: String?,
    val currentBalance: Int,
    val requiredCredits: Int?,
)

/** `show_subscription_expired_modal` + `subscription_info` (rules 29/34): shown once per sign-in. */
data class ExpiredSubscriptionInfo(val planName: String?, val endDate: String?, val creditsBalance: Int?)
