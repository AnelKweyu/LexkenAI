package com.lexken.lexkenai.feature.chat

import com.lexken.lexkenai.core.network.NetworkQualifiers
import com.lexken.lexkenai.feature.chat.data.BriefApi
import com.lexken.lexkenai.feature.chat.data.BundleApi
import com.lexken.lexkenai.feature.chat.data.ChatApi
import com.lexken.lexkenai.feature.chat.data.DraftingApi
import com.lexken.lexkenai.feature.chat.data.ExportApi
import com.lexken.lexkenai.feature.chat.data.FeedbackApi
import com.lexken.lexkenai.feature.chat.data.RemoteBriefRepository
import com.lexken.lexkenai.feature.chat.data.RemoteBundleRepository
import com.lexken.lexkenai.feature.chat.data.RemoteChatRepository
import com.lexken.lexkenai.feature.chat.data.RemoteDraftingRepository
import com.lexken.lexkenai.feature.chat.data.RemoteExportRepository
import com.lexken.lexkenai.feature.chat.data.RemoteFeedbackRepository
import com.lexken.lexkenai.feature.chat.data.RemoteStatuteRepository
import com.lexken.lexkenai.feature.chat.data.StatuteApi
import com.lexken.lexkenai.feature.chat.domain.AddDocumentsToBundleUseCase
import com.lexken.lexkenai.feature.chat.domain.BriefCitationUseCase
import com.lexken.lexkenai.feature.chat.domain.BriefRepository
import com.lexken.lexkenai.feature.chat.domain.BriefSelectedCasesUseCase
import com.lexken.lexkenai.feature.chat.domain.BundleRepository
import com.lexken.lexkenai.feature.chat.domain.CancelTurnUseCase
import com.lexken.lexkenai.feature.chat.domain.ChatRepository
import com.lexken.lexkenai.feature.chat.domain.ConfirmDocumentGenerationUseCase
import com.lexken.lexkenai.feature.chat.domain.DeleteConversationUseCase
import com.lexken.lexkenai.feature.chat.domain.DraftingRepository
import com.lexken.lexkenai.feature.chat.domain.ExplainStatuteUseCase
import com.lexken.lexkenai.feature.chat.domain.ExportAnswerToWordUseCase
import com.lexken.lexkenai.feature.chat.domain.ExportRepository
import com.lexken.lexkenai.feature.chat.domain.FeedbackRepository
import com.lexken.lexkenai.feature.chat.domain.FollowUpUseCase
import com.lexken.lexkenai.feature.chat.domain.GenerateBriefFileUseCase
import com.lexken.lexkenai.feature.chat.domain.GenerationCardStatusUseCase
import com.lexken.lexkenai.feature.chat.domain.ListPlaybookVersionsUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadBriefHistoryUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadConversationsUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadDraftingRegistryUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadPlaybookSkillUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadPlaybookUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadPlaybookVersionUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadThreadUseCase
import com.lexken.lexkenai.feature.chat.domain.OpenDraftDocumentUseCase
import com.lexken.lexkenai.feature.chat.domain.OpenSourceCardUseCase
import com.lexken.lexkenai.feature.chat.domain.RenameConversationUseCase
import com.lexken.lexkenai.feature.chat.domain.ResetPlaybookUseCase
import com.lexken.lexkenai.feature.chat.domain.RestorePlaybookVersionUseCase
import com.lexken.lexkenai.feature.chat.domain.SavePlaybookUseCase
import com.lexken.lexkenai.feature.chat.domain.SendAnswerFeedbackUseCase
import com.lexken.lexkenai.feature.chat.domain.SendTurnUseCase
import com.lexken.lexkenai.feature.chat.domain.SkipBriefingUseCase
import com.lexken.lexkenai.feature.chat.domain.StatuteRepository
import org.koin.core.module.dsl.factoryOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.create

object ChatQualifiers {
    /** `APP_DISPLAY_NAME` — the brand in "<brand> is thinking..." and the export's document name. */
    val BRAND = named("chat.brand")

    /** The host root for `/media/…` downloads (`API_BASE_URL_DOWNLOAD`): the API base without its `/api/`. */
    val MEDIA_BASE_URL = named("chat.mediaBaseUrl")
}

/** `feature/chat` (parity plan F3): the AI workspace's requests, sockets and rules; no UI. */
val chatModule = module {
    single(ChatQualifiers.BRAND) { "LexKen AI" }
    single(ChatQualifiers.MEDIA_BASE_URL) { get<String>(NetworkQualifiers.BASE_URL).trimEnd('/').removeSuffix("/api") }

    single<ChatApi> { get<Retrofit>().create() }
    single<BriefApi> { get<Retrofit>().create() }
    single<StatuteApi> { get<Retrofit>().create() }
    single<FeedbackApi> { get<Retrofit>().create() }
    single<ExportApi> { get<Retrofit>().create() }
    single<DraftingApi> { get<Retrofit>().create() }
    single<BundleApi> { get<Retrofit>().create() }

    single<ChatRepository> { RemoteChatRepository(get(), get()) }
    single<BriefRepository> { RemoteBriefRepository(get(), get()) }
    single<StatuteRepository> { RemoteStatuteRepository(get(), get()) }
    single<FeedbackRepository> { RemoteFeedbackRepository(get()) }
    single<ExportRepository> { RemoteExportRepository(api = get(), plainClient = get(NetworkQualifiers.PLAIN_CLIENT), json = get()) }
    single<DraftingRepository> { RemoteDraftingRepository(get(), get()) }
    single<BundleRepository> { RemoteBundleRepository(get()) }

    factoryOf(::LoadConversationsUseCase)
    factoryOf(::LoadThreadUseCase)
    factoryOf(::RenameConversationUseCase)
    factoryOf(::DeleteConversationUseCase)
    factory { SendTurnUseCase(repository = get(), tracker = get(), apiBaseUrl = get(NetworkQualifiers.BASE_URL), brand = get(ChatQualifiers.BRAND)) }
    factoryOf(::CancelTurnUseCase)
    factoryOf(::SkipBriefingUseCase)
    factoryOf(::BriefSelectedCasesUseCase)
    factory { OpenSourceCardUseCase(briefs = get(), sockets = get(), apiBaseUrl = get(NetworkQualifiers.BASE_URL)) }
    factoryOf(::BriefCitationUseCase)
    factoryOf(::LoadBriefHistoryUseCase)
    factory { FollowUpUseCase(repository = get(), sockets = get(), apiBaseUrl = get(NetworkQualifiers.BASE_URL)) }
    factory { GenerateBriefFileUseCase(repository = get(), polling = get()) }
    factoryOf(::ExplainStatuteUseCase)
    factoryOf(::SendAnswerFeedbackUseCase)
    factory {
        ExportAnswerToWordUseCase(repository = get(), polling = get(), brand = get(ChatQualifiers.BRAND), mediaBaseUrl = get(ChatQualifiers.MEDIA_BASE_URL))
    }
    factory { OpenDraftDocumentUseCase(repository = get(), mediaBaseUrl = get(ChatQualifiers.MEDIA_BASE_URL)) }
    factoryOf(::LoadDraftingRegistryUseCase)
    factoryOf(::LoadPlaybookUseCase)
    factoryOf(::SavePlaybookUseCase)
    factoryOf(::ResetPlaybookUseCase)
    factoryOf(::ListPlaybookVersionsUseCase)
    factoryOf(::LoadPlaybookVersionUseCase)
    factoryOf(::RestorePlaybookVersionUseCase)
    factoryOf(::LoadPlaybookSkillUseCase)
    factoryOf(::ConfirmDocumentGenerationUseCase)
    factoryOf(::AddDocumentsToBundleUseCase)
    factoryOf(::GenerationCardStatusUseCase)
}
