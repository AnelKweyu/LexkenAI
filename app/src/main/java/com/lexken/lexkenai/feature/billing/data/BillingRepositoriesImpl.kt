package com.lexken.lexkenai.feature.billing.data

import com.lexken.lexkenai.core.auth.HttpFailure
import com.lexken.lexkenai.core.auth.httpCall
import com.lexken.lexkenai.core.auth.session.SessionApi
import com.lexken.lexkenai.core.network.safeApiCall
import com.lexken.lexkenai.core.network.safeCall
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.map
import com.lexken.lexkenai.core.result.mapError
import com.lexken.lexkenai.feature.billing.domain.CreditEntry
import com.lexken.lexkenai.feature.billing.domain.CreditHistoryPage
import com.lexken.lexkenai.feature.billing.domain.CreditHistoryRules
import com.lexken.lexkenai.feature.billing.domain.CreditsBalance
import com.lexken.lexkenai.feature.billing.domain.CreditsPurchase
import com.lexken.lexkenai.feature.billing.domain.CreditsRepository
import com.lexken.lexkenai.feature.billing.domain.Discount
import com.lexken.lexkenai.feature.billing.domain.DiscountError
import com.lexken.lexkenai.feature.billing.domain.DiscountGrant
import com.lexken.lexkenai.feature.billing.domain.Invoice
import com.lexken.lexkenai.feature.billing.domain.InvoiceError
import com.lexken.lexkenai.feature.billing.domain.Payment
import com.lexken.lexkenai.feature.billing.domain.PaymentError
import com.lexken.lexkenai.feature.billing.domain.PaymentHistoryRules
import com.lexken.lexkenai.feature.billing.domain.PaymentInitiated
import com.lexken.lexkenai.feature.billing.domain.PaymentMessageRules
import com.lexken.lexkenai.feature.billing.domain.PaymentProvider
import com.lexken.lexkenai.feature.billing.domain.PaymentStatusRead
import com.lexken.lexkenai.feature.billing.domain.PaymentsRepository
import com.lexken.lexkenai.feature.billing.domain.Plan
import com.lexken.lexkenai.feature.billing.domain.PlanCard
import com.lexken.lexkenai.feature.billing.domain.PlansRepository
import com.lexken.lexkenai.feature.billing.domain.Subscription
import com.lexken.lexkenai.feature.billing.domain.SubscriptionPurchase
import com.lexken.lexkenai.feature.billing.domain.SubscriptionRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class RemoteSubscriptionRepository(
    private val api: SubscriptionApi,
    private val sessionApi: SessionApi,
    private val json: Json,
) : SubscriptionRepository {
    override suspend fun current(): Result<Subscription?, DataError> =
        when (val fetched = safeCall { api.current() }) {
            is Result.Success -> Result.Success(fetched.data.toDomain())

            // 404 `{"message": "No subscription information found…", "has_subscription": false}` is a state.
            is Result.Failure -> if (fetched.error == DataError.Network.NOT_FOUND) Result.Success(null) else fetched
        }

    override suspend fun creditsBalance(): Result<CreditsBalance, DataError> = safeCall {
        val dto = sessionApi.creditsBalance()
        CreditsBalance(
            credits = dto.balance,
            resetsOn = dto.resetDate,
            personal = dto.personal,
            subscription = dto.subscription,
            sharedMonthlyLimit = dto.sharedMonthlyLimit,
        )
    }

    override suspend fun requestDiscountCode(email: String): Result<DiscountGrant, DataError> =
        safeApiCall(json) {
            val dto = api.requestDiscountCode(DiscountRequestBody(email))
            DiscountGrant(code = dto.discountCode.orEmpty(), validDays = dto.validDays)
        }
}

internal fun CurrentSubscriptionDto.toDomain(): Subscription? {
    subscription?.let { return it.toDomain(assigned = false, assignmentActive = null) }
    val assignment = assignedSubscription ?: return null
    return assignment.subscription?.toDomain(assigned = true, assignmentActive = assignment.isActive)
}

internal fun SubscriptionDto.toDomain(assigned: Boolean, assignmentActive: Boolean?) = Subscription(
    id = id,
    planName = plan?.name,
    tier = plan?.tier,
    isActive = isActive == true,
    isPaid = isPaid == true,
    isExpired = isExpired == true,
    startDate = startDate,
    endDate = endDate,
    creditsResetDate = creditsResetDate,
    creditsBalance = creditsBalance,
    assigned = assigned,
    assignmentActive = assignmentActive,
)

class RemotePlansRepository(private val api: PlansApi, private val json: Json) : PlansRepository {
    override suspend fun plans(): Result<List<Plan>, DataError> =
        safeCall { api.plans().mapNotNull { it.toDomain() } }

    override suspend fun validateDiscount(code: String, planId: String): Result<Discount, DiscountError> =
        when (val answered = httpCall(json) { api.validateDiscount(DiscountValidateBody(code, planId)) }) {
            is Result.Success -> {
                val dto = answered.data
                val discounted = dto.discountedPrice?.number()
                if (discounted == null) {
                    Result.Failure(DiscountError.Invalid)
                } else {
                    Result.Success(
                        Discount(
                            code = dto.code ?: code,
                            originalPriceKes = dto.originalPrice?.number(),
                            discountedPriceKes = discounted,
                            percentage = dto.discountPercentage?.number(),
                            amountKes = dto.discountAmount?.number(),
                        ),
                    )
                }
            }

            // The web shows one fixed sentence for any error (auth #47).
            is Result.Failure -> {
                if (answered.error.network == DataError.Network.UNAUTHORIZED) {
                    Result.Failure(DiscountError.Transport(answered.error.network))
                } else {
                    Result.Failure(DiscountError.Invalid)
                }
            }
        }

    override suspend fun planCard(): Result<PlanCard, DataError> = safeCall {
        val dto = api.planCard()
        val sub = dto.currentSubscription
        val plan = sub?.plan
        val features = plan?.features
        PlanCard(
            tier = plan?.tier,
            planName = plan?.tierDisplay ?: plan?.name,
            storageLabel = features?.string("storage") ?: features?.string("file_manager_storage") ?: plan?.storage?.let { "$it MB" },
            usersLabel = features?.string("users") ?: features?.string("users_included") ?: plan?.maxEmployees?.toString(),
            activeUsers = sub?.featuresUsage?.activeUsers,
            storageUsedBytes = sub?.featuresUsage?.storageUsedBytes,
            endDate = sub?.endDate,
        )
    }
}

internal fun PlanDto.toDomain(): Plan? {
    val id = id?.contentOrNull ?: return null
    val tier = tier ?: return null
    return Plan(
        id = id,
        tier = tier,
        name = name ?: tier,
        priceKes = price?.number(),
        durationDays = durationDays ?: duration?.let(::durationDays),
        usersIncluded = features?.int("users_included") ?: features?.string("users")?.toIntOrNull(),
    )
}

/** `{days: 30}`, `"30 00:00:00"`, `"P30D"` or a bare number of days. */
internal fun durationDays(element: JsonElement): Int? = runCatching {
    when (element) {
        is JsonObject -> element.int("days")
        is JsonPrimitive -> Regex("^P?([0-9]+)").find(element.content.trim())?.groupValues?.get(1)?.toIntOrNull()
        else -> null
    }
}.getOrNull()

class RemotePaymentsRepository(private val api: PaymentsApi, private val json: Json) : PaymentsRepository {
    override suspend fun history(): Result<List<Payment>, DataError> = safeCall {
        val element = api.history()
        val rows = when (element) {
            is JsonArray -> element
            is JsonObject -> element["results"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        rows.mapNotNull { row -> runCatching { json.decodeFromJsonElement(PaymentDto.serializer(), row) }.getOrNull()?.toDomain() }
    }

    override suspend fun invoiceDownloadUrl(invoiceId: String): Result<String, InvoiceError> =
        when (val answered = safeCall { api.invoiceDownload(invoiceId) }) {
            is Result.Success -> answered.data.downloadUrl?.takeIf { it.isNotBlank() }?.let { Result.Success(it) }
                ?: Result.Failure(InvoiceError.NoLink)

            is Result.Failure -> if (answered.error ==
                DataError.Network.NOT_FOUND
            ) {
                Result.Failure(InvoiceError.NotReady)
            } else {
                Result.Failure(InvoiceError.Transport(answered.error))
            }
        }

    override suspend fun initiateSubscription(purchase: SubscriptionPurchase): Result<PaymentInitiated, PaymentError> {
        val elevation = purchase.elevation?.let { ElevationBody(it.lawFirmName, it.registrationNumber) }
        return when (purchase.provider) {
            PaymentProvider.PAYSTACK -> httpCall(json) {
                api.initiatePaystackSubscription(PaystackSubscriptionBody(purchase.planId, purchase.discountCode?.ifBlank { null }, elevation))
            }.map { it.toDomain() }.mapError { it.toPaymentError() }

            PaymentProvider.DARAJA -> httpCall(json) {
                api.initiateDarajaSubscription(
                    DarajaSubscriptionBody(
                        purchase.phoneNumber.orEmpty(),
                        purchase.planId,
                        purchase.discountCode?.ifBlank {
                            null
                        },
                        elevation,
                    ),
                )
            }.map { it.toDomain() }.mapError { it.toPaymentError() }
        }
    }

    override suspend fun initiateCreditPurchase(purchase: CreditsPurchase): Result<PaymentInitiated, PaymentError> =
        when (purchase.provider) {
            PaymentProvider.PAYSTACK -> httpCall(json) { api.initiatePaystackCredits(PaystackCreditsBody(purchase.amountKes)) }
                .map { it.toDomain() }.mapError { it.toPaymentError() }

            PaymentProvider.DARAJA -> httpCall(json) {
                api.initiateDarajaCredits(DarajaCreditsBody(purchase.phoneNumber.orEmpty(), purchase.amountKes, DarajaCreditsBody.TOP_UP_DESCRIPTION))
            }
                .map { it.toDomain() }.mapError { it.toPaymentError() }
        }

    override suspend fun status(provider: PaymentProvider, paymentId: String): Result<PaymentStatusRead, DataError> = safeCall {
        val dto = when (provider) {
            PaymentProvider.PAYSTACK -> api.paystackStatus(paymentId)
            PaymentProvider.DARAJA -> api.darajaStatus(paymentId)
        }
        PaymentStatusRead(rawStatus = dto.status, receipt = dto.mpesaReceiptNumber, reference = dto.paymentReference ?: dto.reference)
    }
}

internal fun PaymentDto.toDomain(): Payment? {
    val id = id ?: return null
    return Payment(
        id = id,
        createdAt = createdAt,
        updatedAt = updatedAt,
        description = description,
        planName = planName,
        receipt = mpesaReceiptNumber,
        amountKes = amount?.number(),
        status = PaymentHistoryRules.status(status),
        rawStatus = status,
        invoice = invoice?.let { inv -> inv.id?.let { Invoice(it, inv.invoiceNumber, inv.pdfFile) } },
    )
}

internal fun PaystackInitiateDto.toDomain(): PaymentInitiated = PaymentInitiated.Checkout(
    paymentId = paymentId.orEmpty(),
    authorizationUrl = authorizationUrl.orEmpty(),
    accessCode = accessCode,
    publicKey = publicKey,
)

internal fun DarajaInitiateDto.toDomain(): PaymentInitiated = PaymentInitiated.PushSent(paymentId = paymentId.orEmpty(), message = message)

/** auth #48/#52: `data.error || message` rewritten (rule 31); `SUBSCRIPTION_REQUIRED` is its own outcome. */
internal fun HttpFailure.toPaymentError(): PaymentError {
    if (body?.code == "SUBSCRIPTION_REQUIRED") return PaymentError.SubscriptionRequired
    if (network == DataError.Network.UNAUTHORIZED || network == DataError.Network.NO_INTERNET || network == DataError.Network.REQUEST_TIMEOUT) {
        return PaymentError.Transport(network)
    }
    val sentence = body?.error ?: body?.message ?: body?.detail ?: body?.firstSentence
    return PaymentMessageRules.clientMessage(sentence)?.let { PaymentError.Rejected(it) } ?: PaymentError.Transport(network)
}

class RemoteCreditsRepository(private val api: CreditsApi) : CreditsRepository {
    override suspend fun history(page: String?): Result<CreditHistoryPage, DataError> = safeCall {
        val dto = if (page.isNullOrBlank()) api.history() else api.historyPage(page)
        CreditHistoryPage(
            entries = dto.results.mapNotNull { it.toDomain() },
            count = dto.count,
            nextPage = CreditHistoryRules.relativePage(dto.next),
            previousPage = CreditHistoryRules.relativePage(dto.previous),
        )
    }
}

internal fun CreditEntryDto.toDomain(): CreditEntry? {
    val id = id?.contentOrNull ?: return null
    val amount = amount?.intOrNull ?: amount?.doubleOrNull?.toInt() ?: 0
    return CreditEntry(
        id = id,
        timestamp = timestamp,
        feature = feature.orEmpty(),
        featureLabel = CreditHistoryRules.featureLabel(feature),
        amount = amount,
        isRefund = CreditHistoryRules.isRefund(type, isRefund, amount),
        details = details,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
    )
}

private fun JsonPrimitive.number(): Double? = doubleOrNull ?: contentOrNull?.toDoubleOrNull()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
