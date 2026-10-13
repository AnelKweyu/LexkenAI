package com.lexken.lexkenai.feature.billing.domain

import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.core.network.PollEvent
import com.lexken.lexkenai.core.network.PollSchedule
import com.lexken.lexkenai.core.network.PollingEngine
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface SubscriptionRepository {
    /** `GET sub/current/` (auth #42): null on 404 — a state, not an error. */
    suspend fun current(): Result<Subscription?, DataError>

    /** `GET sub/ai-credits-balance/` (auth #43), via the session's reader. */
    suspend fun creditsBalance(): Result<CreditsBalance, DataError>

    /** `POST sub/request_discount_code` (auth #56, no trailing slash) → 201. */
    suspend fun requestDiscountCode(email: String): Result<DiscountGrant, DataError>
}

interface PlansRepository {
    /** `GET sub/plans/` (auth #46), raw rows; [PlanCatalogRules.build] turns them into the page. */
    suspend fun plans(): Result<List<Plan>, DataError>

    /** `POST sub/discounts/validate/ {code, plan_id}` (auth #47). */
    suspend fun validateDiscount(code: String, planId: String): Result<Discount, DiscountError>

    /** `GET auth/api/my-plan-card/` (auth #57). */
    suspend fun planCard(): Result<PlanCard, DataError>
}

interface PaymentsRepository {
    /** `GET sub/payments/` (auth #44): the bare array or a paginated `results`. */
    suspend fun history(): Result<List<Payment>, DataError>

    /** `GET sub/invoices/{id}/download/` (auth #45). */
    suspend fun invoiceDownloadUrl(invoiceId: String): Result<String, InvoiceError>

    /** auth #48 / #49 by provider. */
    suspend fun initiateSubscription(purchase: SubscriptionPurchase): Result<PaymentInitiated, PaymentError>

    /** auth #52 / #53 by provider. */
    suspend fun initiateCreditPurchase(purchase: CreditsPurchase): Result<PaymentInitiated, PaymentError>

    /** auth #50 / #51 by provider: one read of the status route. */
    suspend fun status(provider: PaymentProvider, paymentId: String): Result<PaymentStatusRead, DataError>
}

interface CreditsRepository {
    /** `GET ai/ai-credits-history/` (auth #54), or the page a previous answer's `next` named. */
    suspend fun history(page: String? = null): Result<CreditHistoryPage, DataError>
}

// ── Use cases ───────────────────────────────────────────────────────────────

class LoadSubscriptionUseCase(
    private val repository: SubscriptionRepository,
    private val notice: ExpiredSubscriptionNotice,
) {
    /** Also arms the once-per-sign-in expired notice (rule 34) when the subscription says so. */
    suspend operator fun invoke(): Result<Subscription?, DataError> =
        repository.current().also { result ->
            (result as? Result.Success)?.data?.takeIf { it.isExpired }?.let {
                notice.arm(ExpiredSubscriptionInfo(it.planName, it.endDate, it.creditsBalance))
            }
        }
}

class LoadCreditsBalanceUseCase(private val repository: SubscriptionRepository) {
    suspend operator fun invoke(): Result<CreditsBalance, DataError> = repository.creditsBalance()
}

class LoadPaymentHistoryUseCase(private val repository: PaymentsRepository) {
    suspend operator fun invoke(): Result<List<Payment>, DataError> = repository.history()
}

class GetInvoiceDownloadUrlUseCase(private val repository: PaymentsRepository) {
    suspend operator fun invoke(invoiceId: String): Result<String, InvoiceError> = repository.invoiceDownloadUrl(invoiceId)
}

class LoadPlanCatalogueUseCase(private val repository: PlansRepository) {
    suspend operator fun invoke(currentTier: String? = null): Result<List<CatalogPlan>, DataError> =
        repository.plans().map { PlanCatalogRules.build(it, currentTier) }
}

class ValidateDiscountUseCase(private val repository: PlansRepository) {
    suspend operator fun invoke(code: String, planId: String): Result<Discount, DiscountError> {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return Result.Failure(DiscountError.Invalid)
        return repository.validateDiscount(trimmed, planId)
    }
}

class LoadPlanCardUseCase(private val repository: PlansRepository) {
    suspend operator fun invoke(): Result<PlanCard, DataError> = repository.planCard()
}

class LoadCreditsHistoryUseCase(private val repository: CreditsRepository) {
    suspend operator fun invoke(page: String? = null): Result<CreditHistoryPage, DataError> = repository.history(page)
}

class RequestDiscountCodeUseCase(private val repository: SubscriptionRepository) {
    suspend operator fun invoke(email: String): Result<DiscountGrant, DataError> = repository.requestDiscountCode(email.trim())
}

/** Rule 30 phone + rule 28 elevation gate, then the rail's initiate route. */
class SubscribeUseCase(private val repository: PaymentsRepository) {
    suspend operator fun invoke(purchase: SubscriptionPurchase, elevationRequired: Boolean = false): Result<PaymentInitiated, PaymentError> {
        if (elevationRequired && purchase.elevation == null) return Result.Failure(PaymentError.Invalid(PurchaseProblem.ELEVATION_REQUIRED))
        if (purchase.provider == PaymentProvider.DARAJA) {
            DarajaPhoneRules.validate(purchase.phoneNumber)?.let { return Result.Failure(PaymentError.Invalid(it)) }
        }
        return repository.initiateSubscription(purchase)
    }
}

/** Rule 28: the subscription gate, the amount window, the phone for Daraja, then the rail's route. */
class BuyCreditsUseCase(
    private val payments: PaymentsRepository,
    private val subscriptions: SubscriptionRepository,
    private val today: () -> String,
) {
    suspend operator fun invoke(purchase: CreditsPurchase): Result<PaymentInitiated, PaymentError> {
        // 404 ⇒ no subscription ⇒ blocked; any other error ⇒ assume active (CreditsModal.jsx:74-111).
        val active = when (val current = subscriptions.current()) {
            is Result.Success -> SubscriptionBadgeRules.allowsTopUp(current.data, today())
            is Result.Failure -> true
        }
        CreditsTopUpRules.validate(purchase, active)?.let { return Result.Failure(PaymentError.Invalid(it)) }
        return payments.initiateCreditPurchase(purchase)
    }
}

/**
 * The 5 s / 90 s poll of the rail's status route (polling table): errors are ignored while
 * polling, a 404 ends a Daraja poll as failed, the first terminal status settles it, and the
 * cap settles it as timed out. Runs on [PollingEngine] so the cadence is the shared one.
 */
class PollPurchaseUseCase(
    private val repository: PaymentsRepository,
    private val polling: PollingEngine,
    private val schedule: PollSchedule = PollSchedule(
        initialMillis = PaymentOutcomeRules.POLL_MILLIS,
        maxMillis = PaymentOutcomeRules.POLL_MILLIS,
        capMillis = PaymentOutcomeRules.TIMEOUT_MILLIS,
        backoffFactor = 1.0,
    ),
) {
    data class Settled(val outcome: PaymentOutcome, val receipt: String?, val reference: String?)

    /** Every status read, then the settlement; the flow ends with [Settled]. */
    operator fun invoke(provider: PaymentProvider, paymentId: String): Flow<PurchaseEvent> = flow {
        polling.poll(schedule, isTerminal = { it.outcome != null }) {
            when (val read = repository.status(provider, paymentId)) {
                is Result.Success -> Result.Success(
                    Read(PaymentOutcomeRules.terminal(read.data.rawStatus, provider), read.data.receipt, read.data.reference, read.data.rawStatus),
                )

                is Result.Failure -> when {
                    read.error == DataError.Network.NOT_FOUND && PaymentOutcomeRules.notFoundIsFailure(provider) -> {
                        Result.Success(Read(PaymentOutcome.FAILED, null, null, null))
                    }

                    else -> {
                        read
                    }
                }
            }
        }.collect { event ->
            when (event) {
                is PollEvent.Snapshot -> emit(PurchaseEvent.Pending(event.data.rawStatus))
                is PollEvent.Terminal -> emit(PurchaseEvent.Settled(Settled(event.data.outcome!!, event.data.receipt, event.data.reference)))
                is PollEvent.Stumble -> emit(PurchaseEvent.Pending(null))
                is PollEvent.Failed -> emit(PurchaseEvent.Settled(Settled(PaymentOutcome.FAILED, null, null)))
                PollEvent.Overdue -> emit(PurchaseEvent.Settled(Settled(PaymentOutcome.TIMED_OUT, null, null)))
            }
        }
    }

    private data class Read(val outcome: PaymentOutcome?, val receipt: String?, val reference: String?, val rawStatus: String?)
}

sealed interface PurchaseEvent {
    data class Pending(val rawStatus: String?) : PurchaseEvent

    data class Settled(val result: PollPurchaseUseCase.Settled) : PurchaseEvent
}

/**
 * Rules 29/34: armed once per sign-in from `sub/current/` when `is_expired`, consumed once by
 * the first workspace that mounts, cleared with the session.
 */
class ExpiredSubscriptionNotice : LocalStore {
    @Volatile private var pending: ExpiredSubscriptionInfo? = null

    @Volatile private var shownThisSignIn = false

    fun arm(info: ExpiredSubscriptionInfo) {
        if (!shownThisSignIn) pending = info
    }

    /** The info once, then null until the next sign-in. */
    fun consume(): ExpiredSubscriptionInfo? {
        val info = pending ?: return null
        pending = null
        shownThisSignIn = true
        return info
    }

    override suspend fun clear() {
        pending = null
        shownThisSignIn = false
    }
}

class ConsumeExpiredSubscriptionNoticeUseCase(private val notice: ExpiredSubscriptionNotice) {
    operator fun invoke(): ExpiredSubscriptionInfo? = notice.consume()
}
