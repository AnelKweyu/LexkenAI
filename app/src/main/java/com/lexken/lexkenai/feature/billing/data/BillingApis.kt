package com.lexken.lexkenai.feature.billing.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

/** `subscription/views.py` `CurrentUserSubscriptionView`, `RequestDiscountCodeView` (auth #42, #56). */
interface SubscriptionApi {
    @GET("sub/current/")
    suspend fun current(): CurrentSubscriptionDto

    /** No trailing slash — the route is registered without one and 404s with it. */
    @POST("sub/request_discount_code")
    suspend fun requestDiscountCode(
        @Body body: DiscountRequestBody,
    ): DiscountGrantDto
}

/** `PlanListView`, `ValidateDiscountCodeView` and the authentication app's plan card (auth #46, #47, #57). */
interface PlansApi {
    @GET("sub/plans/")
    suspend fun plans(): List<PlanDto>

    @POST("sub/discounts/validate/")
    suspend fun validateDiscount(
        @Body body: DiscountValidateBody,
    ): DiscountDto

    @GET("auth/api/my-plan-card/")
    suspend fun planCard(): PlanCardDto
}

/** The payment routes of both rails (auth #44, #45, #48–#53). */
interface PaymentsApi {
    /** A bare array on the live server; a paginated `{results}` is tolerated like the web does. */
    @GET("sub/payments/")
    suspend fun history(): JsonElement

    @GET("sub/invoices/{invoiceId}/download/")
    suspend fun invoiceDownload(
        @Path("invoiceId") invoiceId: String,
    ): InvoiceDownloadDto

    @POST("sub/payments/paystack/initiate/")
    suspend fun initiatePaystackSubscription(
        @Body body: PaystackSubscriptionBody,
    ): PaystackInitiateDto

    @POST("sub/payments/initiate/")
    suspend fun initiateDarajaSubscription(
        @Body body: DarajaSubscriptionBody,
    ): DarajaInitiateDto

    @POST("sub/payments/paystack/initiate_credit_purchase/")
    suspend fun initiatePaystackCredits(
        @Body body: PaystackCreditsBody,
    ): PaystackInitiateDto

    @POST("sub/payments/initiate_credit_purchase/")
    suspend fun initiateDarajaCredits(
        @Body body: DarajaCreditsBody,
    ): DarajaInitiateDto

    @GET("sub/payments/paystack/status/{paymentId}/")
    suspend fun paystackStatus(
        @Path("paymentId") paymentId: String,
    ): PaymentStatusDto

    @GET("sub/payments/status/{paymentId}/")
    suspend fun darajaStatus(
        @Path("paymentId") paymentId: String,
    ): PaymentStatusDto
}

/** `ai/views.py` `AICreditsHistoryView` (auth #54): DRF page of 5, `next` followed as a relative path. */
interface CreditsApi {
    @GET("ai/ai-credits-history/")
    suspend fun history(): CreditsHistoryDto

    @GET
    suspend fun historyPage(
        @Url relativePage: String,
    ): CreditsHistoryDto
}

// ── DTOs ────────────────────────────────────────────────────────────────────

@Serializable
data class CurrentSubscriptionDto(
    val subscription: SubscriptionDto? = null,
    @SerialName("assigned_subscription") val assignedSubscription: AssignmentDto? = null,
)

@Serializable
data class AssignmentDto(
    val id: String? = null,
    val subscription: SubscriptionDto? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("monthly_credit_limit") val monthlyCreditLimit: Int? = null,
    @SerialName("current_credits_used") val currentCreditsUsed: Int? = null,
)

@Serializable
data class SubscriptionDto(
    val id: String? = null,
    val plan: PlanDto? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("is_paid") val isPaid: Boolean? = null,
    @SerialName("is_expired") val isExpired: Boolean? = null,
    @SerialName("ai_credits_balance") val creditsBalance: Int? = null,
    @SerialName("ai_credits_reset_date") val creditsResetDate: String? = null,
)

/** `PlanSerializer`: `price` is a decimal string, `duration` a Django duration string ("30 00:00:00"); older shapes tolerated. */
@Serializable
data class PlanDto(
    val id: JsonPrimitive? = null,
    val tier: String? = null,
    val name: String? = null,
    val price: JsonPrimitive? = null,
    val duration: JsonElement? = null,
    @SerialName("duration_days") val durationDays: Int? = null,
    val features: JsonObject? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class DiscountRequestBody(val email: String)

@Serializable
data class DiscountGrantDto(
    val message: String? = null,
    @SerialName("discount_code") val discountCode: String? = null,
    @SerialName("valid_days") val validDays: Int? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    @SerialName("plan_tier") val planTier: String? = null,
    @SerialName("discount_percentage") val discountPercentage: JsonPrimitive? = null,
)

@Serializable
data class DiscountValidateBody(
    val code: String,
    @SerialName("plan_id") val planId: String,
)

/** Raw decimals in a hand-built `Response`, so floats on the wire; strings tolerated. */
@Serializable
data class DiscountDto(
    val message: String? = null,
    val code: String? = null,
    @SerialName("original_price") val originalPrice: JsonPrimitive? = null,
    @SerialName("discounted_price") val discountedPrice: JsonPrimitive? = null,
    @SerialName("discount_percentage") val discountPercentage: JsonPrimitive? = null,
    @SerialName("discount_amount") val discountAmount: JsonPrimitive? = null,
)

@Serializable
data class PlanCardDto(
    @SerialName("all_plans") val allPlans: List<PlanDto> = emptyList(),
    @SerialName("current_subscription") val currentSubscription: PlanCardSubscriptionDto? = null,
)

@Serializable
data class PlanCardSubscriptionDto(
    val id: String? = null,
    val plan: PlanCardPlanDto? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("features_usage") val featuresUsage: FeaturesUsageDto? = null,
)

/** The authentication app's `PlanSerializer(fields='__all__')`. */
@Serializable
data class PlanCardPlanDto(
    val id: String? = null,
    val tier: String? = null,
    @SerialName("tier_display") val tierDisplay: String? = null,
    val name: String? = null,
    val price: JsonPrimitive? = null,
    val features: JsonObject? = null,
    val storage: Int? = null,
    @SerialName("max_employees") val maxEmployees: Int? = null,
)

@Serializable
data class FeaturesUsageDto(
    @SerialName("storage_used_bytes") val storageUsedBytes: Long? = null,
    @SerialName("active_users") val activeUsers: Int? = null,
)

@Serializable
data class PaymentDto(
    val id: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val description: String? = null,
    @SerialName("plan_name") val planName: String? = null,
    @SerialName("mpesa_receipt_number") val mpesaReceiptNumber: String? = null,
    val amount: JsonPrimitive? = null,
    val status: String? = null,
    val invoice: InvoiceDto? = null,
)

@Serializable
data class InvoiceDto(
    val id: String? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("pdf_file") val pdfFile: String? = null,
)

@Serializable
data class InvoiceDownloadDto(
    @SerialName("download_url") val downloadUrl: String? = null,
)

@Serializable
data class ElevationBody(
    @SerialName("law_firm_name") val lawFirmName: String,
    @SerialName("registration_number") val registrationNumber: String,
)

/** auth #48: the web sends `phone_number: undefined`, i.e. omits it. */
@Serializable
data class PaystackSubscriptionBody(
    @SerialName("plan_id") val planId: String,
    @SerialName("discount_code") val discountCode: String? = null,
    @SerialName("elevation_details") val elevationDetails: ElevationBody? = null,
)

@Serializable
data class DarajaSubscriptionBody(
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("discount_code") val discountCode: String? = null,
    @SerialName("elevation_details") val elevationDetails: ElevationBody? = null,
)

@Serializable
data class PaystackCreditsBody(val amount: Int)

/** auth #53: the web always sends `transaction_desc: "AICreditTopUp"`; no default, since the Json does not encode defaults. */
@Serializable
data class DarajaCreditsBody(
    @SerialName("phone_number") val phoneNumber: String,
    val amount: Int,
    @SerialName("transaction_desc") val transactionDesc: String,
) {
    companion object {
        const val TOP_UP_DESCRIPTION = "AICreditTopUp"
    }
}

@Serializable
data class PaystackInitiateDto(
    val message: String? = null,
    val provider: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    val reference: String? = null,
    @SerialName("authorization_url") val authorizationUrl: String? = null,
    @SerialName("access_code") val accessCode: String? = null,
    @SerialName("public_key") val publicKey: String? = null,
)

@Serializable
data class DarajaInitiateDto(
    val message: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("merchant_request_id") val merchantRequestId: String? = null,
    @SerialName("checkout_request_id") val checkoutRequestId: String? = null,
)

@Serializable
data class PaymentStatusDto(
    val id: String? = null,
    val status: String? = null,
    val amount: JsonPrimitive? = null,
    @SerialName("mpesa_receipt_number") val mpesaReceiptNumber: String? = null,
    val reference: String? = null,
    @SerialName("payment_reference") val paymentReference: String? = null,
    val description: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class CreditsHistoryDto(
    val count: Int? = null,
    val next: String? = null,
    val previous: String? = null,
    val results: List<CreditEntryDto> = emptyList(),
)

@Serializable
data class CreditEntryDto(
    val id: JsonPrimitive? = null,
    val timestamp: String? = null,
    val feature: String? = null,
    val amount: JsonPrimitive? = null,
    val details: String? = null,
    val type: String? = null,
    @SerialName("is_refund") val isRefund: Boolean? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)
