package com.lexken.lexkenai.core.auth

import com.lexken.lexkenai.core.auth.session.ChangePasswordUseCase
import com.lexken.lexkenai.core.auth.session.ObserveSessionUseCase
import com.lexken.lexkenai.core.auth.session.PasswordChanger
import com.lexken.lexkenai.core.auth.session.RefreshSessionUseCase
import com.lexken.lexkenai.core.auth.session.RemoteSessionRepository
import com.lexken.lexkenai.core.auth.session.SessionApi
import com.lexken.lexkenai.core.auth.session.SessionRepository
import com.lexken.lexkenai.core.auth.session.SignOutUseCase
import com.lexken.lexkenai.core.network.JSON_MEDIA_TYPE
import com.lexken.lexkenai.core.network.NetworkQualifiers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

private val VF_RETROFIT = named("auth.vfRetrofit")
private val EXCHANGE_RETROFIT = named("auth.exchangeRetrofit")

/**
 * The identity host (VF/EMS) for sign-up, the codes, the reset, the password change and the
 * sign-out revocation; VF OAuth mint → identity exchange → Lawris JWT for the session. The
 * identity-host and exchange calls run on the plain client (no stored bearer, no authenticator)
 * so token renewal can never recurse; the Lawris leftovers and the session reads use the shared
 * authed client. Requires a [VfConfig] binding and the network module.
 */
val authModule = module {
    single<TokenStore> { DataStoreTokenStore(androidContext()) }

    single(VF_RETROFIT) {
        Retrofit.Builder()
            .baseUrl(get<VfConfig>().baseUrl)
            .client(get<OkHttpClient>(NetworkQualifiers.PLAIN_CLIENT))
            .addConverterFactory(get<Json>().asConverterFactory(JSON_MEDIA_TYPE))
            .build()
    }
    single(EXCHANGE_RETROFIT) {
        Retrofit.Builder()
            .baseUrl(get<String>(NetworkQualifiers.BASE_URL))
            .client(get<OkHttpClient>(NetworkQualifiers.PLAIN_CLIENT))
            .addConverterFactory(get<Json>().asConverterFactory(JSON_MEDIA_TYPE))
            .build()
    }
    single<VfOAuthApi> { get<Retrofit>(VF_RETROFIT).create() }
    single<VfAccountApi> { get<Retrofit>(VF_RETROFIT).create() }
    single<ExchangeApi> { get<Retrofit>(EXCHANGE_RETROFIT).create() }
    single<LawrisAuthApi> { get<Retrofit>().create() }
    single<SessionApi> { get<Retrofit>().create() }

    single { AuthSessionRenewer(get(), get(), get()) } bind SessionRenewer::class
    single { AuthSessionRevoker(get(), get(), get()) } bind SessionRevoker::class
    single { RemoteSessionRepository(api = get(), tokenStore = get(), revoker = get()) } bind SessionRepository::class
    single {
        IdentityPasswordChanger(vfApi = get(), accountApi = get(), config = get(), tokenStore = get(), json = get())
    } bind PasswordChanger::class
    single {
        AuthSession(
            vfApi = get(),
            exchangeApi = get(),
            accountApi = get(),
            lawrisApi = get(),
            config = get(),
            tokenStore = get(),
            sessionRepository = get(),
            json = get(),
        )
    } bind AuthRepository::class

    factoryOf(::SignInUseCase)
    factoryOf(::VerifyOtpUseCase)
    factoryOf(::RegisterUseCase)
    factoryOf(::VerifyEmailOtpUseCase)
    factoryOf(::ResendEmailOtpUseCase)
    factoryOf(::RequestPhoneOtpUseCase)
    factoryOf(::VerifyPhoneOtpUseCase)
    factoryOf(::CompletePendingSignInUseCase)
    factoryOf(::RequestPasswordResetUseCase)
    factoryOf(::ConfirmPasswordResetUseCase)
    factoryOf(::RequestDistressHelpUseCase)
    factoryOf(::VerifyResetTokenUseCase)
    factoryOf(::ConfirmResetWithTokenUseCase)
    factoryOf(::ObserveSessionUseCase)
    factoryOf(::RefreshSessionUseCase)
    factoryOf(::SignOutUseCase)
    factoryOf(::ChangePasswordUseCase)
}
