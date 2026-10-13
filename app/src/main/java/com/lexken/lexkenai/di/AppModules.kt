package com.lexken.lexkenai.di

import com.lexken.lexkenai.BuildConfig
import com.lexken.lexkenai.core.auth.VfConfig
import com.lexken.lexkenai.core.auth.authModule
import com.lexken.lexkenai.core.network.NetworkQualifiers
import com.lexken.lexkenai.core.network.aisession.DataStoreDeviceIdStore
import com.lexken.lexkenai.core.network.aisession.DeviceIdStore
import com.lexken.lexkenai.core.network.networkModule
import com.lexken.lexkenai.feature.account.accountModule
import com.lexken.lexkenai.feature.billing.BillingQualifiers
import com.lexken.lexkenai.feature.billing.billingModule
import com.lexken.lexkenai.feature.chat.chatModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Values core and the features need from BuildConfig: the flavour's hosts, the identity-host credentials, the payment rail. */
private val buildConfigModule = module {
    single(NetworkQualifiers.BASE_URL) { BuildConfig.API_BASE_URL }
    single(NetworkQualifiers.DEBUG_LOGGING) { BuildConfig.DEBUG }
    single(BillingQualifiers.PAYMENT_PROVIDER_NAME) { BuildConfig.PAYMENT_PROVIDER }
    single {
        VfConfig(
            baseUrl = BuildConfig.VF_API_BASE_URL,
            clientId = BuildConfig.VF_CLIENT_ID,
            clientSecret = BuildConfig.VF_CLIENT_SECRET,
            bypassKey = BuildConfig.VF_AUTH_BYPASS,
            bundleId = BuildConfig.VF_BUNDLE_ID,
        )
    }
}

/** Stores that need the Android context; tests bind their in-memory twins instead. */
private val platformModule = module {
    single<DeviceIdStore> { DataStoreDeviceIdStore(androidContext()) }
}

/** Everything Koin starts with. Order: build config → platform → core → features. */
val appModules: List<Module> = listOf(
    buildConfigModule,
    platformModule,
    networkModule,
    authModule,
    accountModule,
    billingModule,
    chatModule,
)
