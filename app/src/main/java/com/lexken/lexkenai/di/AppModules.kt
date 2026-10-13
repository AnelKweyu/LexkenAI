package com.lexken.lexkenai.di

import com.lexken.lexkenai.BuildConfig
import com.lexken.lexkenai.core.auth.VfConfig
import com.lexken.lexkenai.core.auth.authModule
import com.lexken.lexkenai.core.network.NetworkQualifiers
import com.lexken.lexkenai.core.network.networkModule
import org.koin.core.module.Module
import org.koin.dsl.module

/** Values core needs from BuildConfig: the flavour's hosts and the identity-host credentials. */
private val buildConfigModule = module {
    single(NetworkQualifiers.BASE_URL) { BuildConfig.API_BASE_URL }
    single(NetworkQualifiers.DEBUG_LOGGING) { BuildConfig.DEBUG }
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

/** Everything Koin starts with. Order: build config → core → features. */
val appModules: List<Module> = listOf(
    buildConfigModule,
    networkModule,
    authModule,
)
