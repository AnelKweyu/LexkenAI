package com.lexken.lexkenai.feature.billing

import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.feature.billing.data.CreditsApi
import com.lexken.lexkenai.feature.billing.data.PaymentsApi
import com.lexken.lexkenai.feature.billing.data.PlansApi
import com.lexken.lexkenai.feature.billing.data.RemoteCreditsRepository
import com.lexken.lexkenai.feature.billing.data.RemotePaymentsRepository
import com.lexken.lexkenai.feature.billing.data.RemotePlansRepository
import com.lexken.lexkenai.feature.billing.data.RemoteSubscriptionRepository
import com.lexken.lexkenai.feature.billing.data.SubscriptionApi
import com.lexken.lexkenai.feature.billing.domain.BuyCreditsUseCase
import com.lexken.lexkenai.feature.billing.domain.ConsumeExpiredSubscriptionNoticeUseCase
import com.lexken.lexkenai.feature.billing.domain.CreditsRepository
import com.lexken.lexkenai.feature.billing.domain.ExpiredSubscriptionNotice
import com.lexken.lexkenai.feature.billing.domain.GetInvoiceDownloadUrlUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadCreditsBalanceUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadCreditsHistoryUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadPaymentHistoryUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadPlanCardUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadPlanCatalogueUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadSubscriptionUseCase
import com.lexken.lexkenai.feature.billing.domain.PaymentProvider
import com.lexken.lexkenai.feature.billing.domain.PaymentProviderRules
import com.lexken.lexkenai.feature.billing.domain.PaymentsRepository
import com.lexken.lexkenai.feature.billing.domain.PlansRepository
import com.lexken.lexkenai.feature.billing.domain.PollPurchaseUseCase
import com.lexken.lexkenai.feature.billing.domain.RequestDiscountCodeUseCase
import com.lexken.lexkenai.feature.billing.domain.SubscribeUseCase
import com.lexken.lexkenai.feature.billing.domain.SubscriptionRepository
import com.lexken.lexkenai.feature.billing.domain.ValidateDiscountUseCase
import org.koin.core.module.dsl.factoryOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.create
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BillingQualifiers {
    /** The configured rail name (`paystack` / `daraja`), supplied by the app from BuildConfig. */
    val PAYMENT_PROVIDER_NAME = named("billing.paymentProviderName")
}

/**
 * `feature/billing` (parity plan F2). Requires a `String` under
 * [BillingQualifiers.PAYMENT_PROVIDER_NAME]; the resolved [PaymentProvider] is a single.
 */
val billingModule = module {
    single<SubscriptionApi> { get<Retrofit>().create() }
    single<PlansApi> { get<Retrofit>().create() }
    single<PaymentsApi> { get<Retrofit>().create() }
    single<CreditsApi> { get<Retrofit>().create() }

    single<SubscriptionRepository> { RemoteSubscriptionRepository(api = get(), sessionApi = get(), json = get()) }
    single<PlansRepository> { RemotePlansRepository(get(), get()) }
    single<PaymentsRepository> { RemotePaymentsRepository(get(), get()) }
    single<CreditsRepository> { RemoteCreditsRepository(get()) }

    single { PaymentProviderRules.resolve(get<String>(BillingQualifiers.PAYMENT_PROVIDER_NAME)) }
    single { ExpiredSubscriptionNotice() } bind LocalStore::class

    factoryOf(::LoadSubscriptionUseCase)
    factoryOf(::LoadCreditsBalanceUseCase)
    factoryOf(::LoadPaymentHistoryUseCase)
    factoryOf(::GetInvoiceDownloadUrlUseCase)
    factoryOf(::LoadPlanCatalogueUseCase)
    factoryOf(::ValidateDiscountUseCase)
    factoryOf(::LoadPlanCardUseCase)
    factoryOf(::LoadCreditsHistoryUseCase)
    factoryOf(::RequestDiscountCodeUseCase)
    factoryOf(::SubscribeUseCase)
    // ISO date without java.time: minSdk 24 has no LocalDate and desugaring is off.
    factory { BuyCreditsUseCase(payments = get(), subscriptions = get(), today = { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }) }
    factory { PollPurchaseUseCase(repository = get(), polling = get()) }
    factoryOf(::ConsumeExpiredSubscriptionNoticeUseCase)
}
