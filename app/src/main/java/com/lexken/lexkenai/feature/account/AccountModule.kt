package com.lexken.lexkenai.feature.account

import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.feature.account.data.AccountDeletionApi
import com.lexken.lexkenai.feature.account.data.DataStoreOnboardingStore
import com.lexken.lexkenai.feature.account.data.DataStoreProductUpdateStore
import com.lexken.lexkenai.feature.account.data.LawFirmApi
import com.lexken.lexkenai.feature.account.data.ProductUpdatesApi
import com.lexken.lexkenai.feature.account.data.ProfileApi
import com.lexken.lexkenai.feature.account.data.RemoteAccountRepository
import com.lexken.lexkenai.feature.account.data.RemoteLawFirmRepository
import com.lexken.lexkenai.feature.account.data.RemoteProductUpdatesRepository
import com.lexken.lexkenai.feature.account.data.RemoteProfileRepository
import com.lexken.lexkenai.feature.account.data.RemoteSupportRepository
import com.lexken.lexkenai.feature.account.data.SmtpApi
import com.lexken.lexkenai.feature.account.data.SupportApi
import com.lexken.lexkenai.feature.account.domain.AccountRepository
import com.lexken.lexkenai.feature.account.domain.CheckProductUpdateUseCase
import com.lexken.lexkenai.feature.account.domain.CompleteDmsTourUseCase
import com.lexken.lexkenai.feature.account.domain.DecideTourUseCase
import com.lexken.lexkenai.feature.account.domain.DeleteAccountUseCase
import com.lexken.lexkenai.feature.account.domain.LawFirmRepository
import com.lexken.lexkenai.feature.account.domain.LawFirmSetupUseCase
import com.lexken.lexkenai.feature.account.domain.LoadLawFirmProfileUseCase
import com.lexken.lexkenai.feature.account.domain.LoadProfileUseCase
import com.lexken.lexkenai.feature.account.domain.MarkProductUpdateSeenUseCase
import com.lexken.lexkenai.feature.account.domain.ObserveOnboardingUseCase
import com.lexken.lexkenai.feature.account.domain.OnboardingStore
import com.lexken.lexkenai.feature.account.domain.ProductUpdateStore
import com.lexken.lexkenai.feature.account.domain.ProductUpdatesRepository
import com.lexken.lexkenai.feature.account.domain.ProfileRepository
import com.lexken.lexkenai.feature.account.domain.SaveLawFirmProfileUseCase
import com.lexken.lexkenai.feature.account.domain.SendFeedbackUseCase
import com.lexken.lexkenai.feature.account.domain.SignOutAndWipeUseCase
import com.lexken.lexkenai.feature.account.domain.SkipLawFirmSetupUseCase
import com.lexken.lexkenai.feature.account.domain.SupportRepository
import com.lexken.lexkenai.feature.account.domain.UpdateNotificationPreferencesUseCase
import com.lexken.lexkenai.feature.account.domain.UploadAvatarUseCase
import com.lexken.lexkenai.feature.account.domain.UploadLawFirmLogoUseCase
import com.lexken.lexkenai.feature.account.domain.WipeLocalStateUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.bind
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.create

/**
 * `feature/account` (parity plan F1): the signed-in account surface on the shared authed
 * Retrofit. The two DataStore-backed stores are bound as [LocalStore] too, so sign-out and
 * account deletion wipe them with everything else.
 */
val accountModule = module {
    single<ProfileApi> { get<Retrofit>().create() }
    single<LawFirmApi> { get<Retrofit>().create() }
    single<SupportApi> { get<Retrofit>().create() }
    single<AccountDeletionApi> { get<Retrofit>().create() }
    single<ProductUpdatesApi> { get<Retrofit>().create() }
    single<SmtpApi> { get<Retrofit>().create() }

    single<ProfileRepository> { RemoteProfileRepository(get()) }
    single<LawFirmRepository> { RemoteLawFirmRepository(get(), get(), get()) }
    single<SupportRepository> { RemoteSupportRepository(get(), get()) }
    single<AccountRepository> { RemoteAccountRepository(get(), get()) }
    single<ProductUpdatesRepository> { RemoteProductUpdatesRepository(get()) }

    single<OnboardingStore> { DataStoreOnboardingStore(androidContext()) } bind LocalStore::class
    single<ProductUpdateStore> { DataStoreProductUpdateStore(androidContext()) } bind LocalStore::class

    factory { WipeLocalStateUseCase(stores = getAll(), tokenStore = get(), session = get()) }
    factory { SignOutAndWipeUseCase(stores = getAll(), session = get()) }
    factoryOf(::LoadProfileUseCase)
    factoryOf(::UploadAvatarUseCase)
    factoryOf(::UpdateNotificationPreferencesUseCase)
    factoryOf(::LoadLawFirmProfileUseCase)
    factoryOf(::SaveLawFirmProfileUseCase)
    factoryOf(::UploadLawFirmLogoUseCase)
    factoryOf(::LawFirmSetupUseCase)
    factoryOf(::SkipLawFirmSetupUseCase)
    factoryOf(::SendFeedbackUseCase)
    factoryOf(::DeleteAccountUseCase)
    factoryOf(::ObserveOnboardingUseCase)
    factoryOf(::DecideTourUseCase)
    factoryOf(::CompleteDmsTourUseCase)
    factoryOf(::CheckProductUpdateUseCase)
    factoryOf(::MarkProductUpdateSeenUseCase)
}
