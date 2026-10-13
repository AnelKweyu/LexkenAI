package com.lexken.lexkenai.feature.billing.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.lexken.lexkenai.core.network.PollingEngine
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** T3 for F2: the purchase poll per rail, the credits gate, the subscribe gate and the expired notice. */
class BillingFlowsTest {
    /** Status reads scripted per call; the last one repeats. */
    private class ScriptedPayments(private val reads: List<Result<PaymentStatusRead, DataError>>) : PaymentsRepository {
        var statusCalls = 0
        val initiated = mutableListOf<Any>()

        override suspend fun history() = Result.Success(emptyList<Payment>())

        override suspend fun invoiceDownloadUrl(invoiceId: String) = Result.Failure(InvoiceError.NoLink)

        override suspend fun initiateSubscription(purchase: SubscriptionPurchase): Result<PaymentInitiated, PaymentError> =
            Result.Success(PaymentInitiated.Checkout("pay-1", "https://checkout", null, null)).also { initiated += purchase }

        override suspend fun initiateCreditPurchase(purchase: CreditsPurchase): Result<PaymentInitiated, PaymentError> =
            Result.Success(PaymentInitiated.PushSent("pay-2", "STK push sent")).also { initiated += purchase }

        override suspend fun status(provider: PaymentProvider, paymentId: String): Result<PaymentStatusRead, DataError> =
            reads[minOf(statusCalls, reads.lastIndex)].also { statusCalls++ }
    }

    private fun read(status: String?, receipt: String? = null) = Result.Success(PaymentStatusRead(status, receipt, null))

    private fun poll(repository: PaymentsRepository) = PollPurchaseUseCase(repository, PollingEngine())

    @Test
    fun `the poll settles on the first SUCCESS after the pending reads, carrying the receipt`() = runTest {
        val payments = ScriptedPayments(listOf(read("PENDING"), read("PENDING"), read("SUCCESS", "QHK8L3M0NP")))

        val events = poll(payments)(PaymentProvider.DARAJA, "pay-2").toList()

        assertThat(events).containsExactly(
            PurchaseEvent.Pending("PENDING"),
            PurchaseEvent.Pending("PENDING"),
            PurchaseEvent.Settled(PollPurchaseUseCase.Settled(PaymentOutcome.SUCCESS, "QHK8L3M0NP", null)),
        )
        assertThat(payments.statusCalls).isEqualTo(3)
    }

    @Test
    fun `a paystack CANCELLED keeps polling while a daraja CANCELLED settles`() = runTest {
        val paystack = ScriptedPayments(listOf(read("CANCELLED"), read("SUCCESS")))
        val daraja = ScriptedPayments(listOf(read("CANCELLED"), read("SUCCESS")))

        val paystackEvents = poll(paystack)(PaymentProvider.PAYSTACK, "pay-1").toList()
        val darajaEvents = poll(daraja)(PaymentProvider.DARAJA, "pay-2").toList()

        assertThat(paystackEvents.last()).isEqualTo(PurchaseEvent.Settled(PollPurchaseUseCase.Settled(PaymentOutcome.SUCCESS, null, null)))
        assertThat(paystack.statusCalls).isEqualTo(2)
        assertThat(darajaEvents).containsExactly(PurchaseEvent.Settled(PollPurchaseUseCase.Settled(PaymentOutcome.CANCELLED, null, null)))
    }

    @Test
    fun `errors are ignored while polling, a daraja 404 fails, a paystack 404 keeps polling`() = runTest {
        val flaky = ScriptedPayments(listOf(Result.Failure(DataError.Network.SERVER_ERROR), read("SUCCESS")))
        val darajaMissing = ScriptedPayments(listOf(Result.Failure(DataError.Network.NOT_FOUND)))
        val paystackMissing = ScriptedPayments(listOf(Result.Failure(DataError.Network.NOT_FOUND), read("SUCCESS")))

        assertThat(poll(flaky)(PaymentProvider.PAYSTACK, "p").toList()).containsExactly(
            PurchaseEvent.Pending(null),
            PurchaseEvent.Settled(PollPurchaseUseCase.Settled(PaymentOutcome.SUCCESS, null, null)),
        )
        assertThat(poll(darajaMissing)(PaymentProvider.DARAJA, "p").toList())
            .containsExactly(PurchaseEvent.Settled(PollPurchaseUseCase.Settled(PaymentOutcome.FAILED, null, null)))
        assertThat(poll(paystackMissing)(PaymentProvider.PAYSTACK, "p").toList().last())
            .isEqualTo(PurchaseEvent.Settled(PollPurchaseUseCase.Settled(PaymentOutcome.SUCCESS, null, null)))
    }

    @Test
    fun `ninety seconds without a terminal status times out, polling every five seconds`() = runTest {
        val forever = ScriptedPayments(listOf(read("PENDING")))

        val events = poll(forever)(PaymentProvider.PAYSTACK, "p").toList()

        assertThat(events.last()).isEqualTo(PurchaseEvent.Settled(PollPurchaseUseCase.Settled(PaymentOutcome.TIMED_OUT, null, null)))
        assertThat(forever.statusCalls).isGreaterThanOrEqualTo(18)
        assertThat(testScheduler.currentTime).isEqualTo(90_000L)
    }

    @Test
    fun `buying credits is blocked without a subscription, assumed allowed on another error, and gated by the rules`() = runTest {
        val payments = ScriptedPayments(emptyList())
        var answer: Result<Subscription?, DataError> = Result.Success(null)
        val subscriptions = object : SubscriptionRepository {
            override suspend fun current() = answer

            override suspend fun creditsBalance() = Result.Failure(DataError.Network.UNKNOWN)

            override suspend fun requestDiscountCode(email: String) = Result.Failure(DataError.Network.UNKNOWN)
        }
        val useCase = BuyCreditsUseCase(payments, subscriptions, today = { "2026-10-13" })

        assertThat(
            useCase(CreditsPurchase(500, PaymentProvider.PAYSTACK)).errorOrNull(),
        ).isEqualTo(PaymentError.Invalid(PurchaseProblem.NO_ACTIVE_SUBSCRIPTION))
        answer = Result.Failure(DataError.Network.SERVER_ERROR)
        assertThat(useCase(CreditsPurchase(500, PaymentProvider.PAYSTACK)).getOrNull()!!).isInstanceOf(PaymentInitiated.PushSent::class)
        answer = Result.Success(Subscription("s", "Solo", "SOLO", true, true, false, null, "2026-12-01", null, 10))
        assertThat(useCase(CreditsPurchase(800, PaymentProvider.PAYSTACK)).errorOrNull()).isEqualTo(PaymentError.Invalid(PurchaseProblem.AMOUNT_ABOVE_MAXIMUM))
        assertThat(
            useCase(CreditsPurchase(500, PaymentProvider.DARAJA, "0822334455")).errorOrNull(),
        ).isEqualTo(PaymentError.Invalid(PurchaseProblem.PHONE_INVALID))
        assertThat(useCase(CreditsPurchase(500, PaymentProvider.DARAJA, "0722334455")).getOrNull()!!).isInstanceOf(PaymentInitiated.PushSent::class)
        assertThat(payments.initiated).hasSize(2)
    }

    @Test
    fun `subscribing needs elevation details when required and a valid phone on daraja`() = runTest {
        val payments = ScriptedPayments(emptyList())
        val useCase = SubscribeUseCase(payments)

        assertThat(useCase(SubscriptionPurchase("p", PaymentProvider.PAYSTACK), elevationRequired = true).errorOrNull())
            .isEqualTo(PaymentError.Invalid(PurchaseProblem.ELEVATION_REQUIRED))
        assertThat(useCase(SubscriptionPurchase("p", PaymentProvider.DARAJA, phoneNumber = "+254722334455")).errorOrNull())
            .isEqualTo(PaymentError.Invalid(PurchaseProblem.PHONE_INVALID))
        assertThat(useCase(SubscriptionPurchase("p", PaymentProvider.PAYSTACK)).getOrNull()!!).isInstanceOf(PaymentInitiated.Checkout::class)
        assertThat(payments.initiated).hasSize(1)
    }

    @Test
    fun `loading an expired subscription arms the notice once per sign-in`() = runTest {
        val notice = ExpiredSubscriptionNotice()
        val expired =
            Subscription(
                "s",
                "Solo",
                "SOLO",
                isActive = false,
                isPaid = true,
                isExpired = true,
                startDate = null,
                endDate = "2026-10-01",
                creditsResetDate = null,
                creditsBalance = 0,
            )
        val repository = object : SubscriptionRepository {
            override suspend fun current(): Result<Subscription?, DataError> = Result.Success(expired)

            override suspend fun creditsBalance() = Result.Failure(DataError.Network.UNKNOWN)

            override suspend fun requestDiscountCode(email: String) = Result.Failure(DataError.Network.UNKNOWN)
        }

        LoadSubscriptionUseCase(repository, notice)()
        LoadSubscriptionUseCase(repository, notice)()

        assertThat(ConsumeExpiredSubscriptionNoticeUseCase(notice)()).isEqualTo(ExpiredSubscriptionInfo("Solo", "2026-10-01", 0))
        assertThat(ConsumeExpiredSubscriptionNoticeUseCase(notice)()).isNull()
        LoadSubscriptionUseCase(repository, notice)()
        assertThat(ConsumeExpiredSubscriptionNoticeUseCase(notice)()).isNull()
    }
}
