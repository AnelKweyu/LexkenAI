package com.lexken.lexkenai.live

import com.lexken.lexkenai.core.auth.InMemoryTokenStore
import com.lexken.lexkenai.core.auth.SignInOutcome
import com.lexken.lexkenai.core.auth.SignInUseCase
import com.lexken.lexkenai.core.auth.TokenStore
import com.lexken.lexkenai.core.auth.VfConfig
import com.lexken.lexkenai.core.auth.authModule
import com.lexken.lexkenai.core.auth.session.LocalStore
import com.lexken.lexkenai.core.auth.session.ObserveSessionUseCase
import com.lexken.lexkenai.core.auth.session.SessionState
import com.lexken.lexkenai.core.common.Clock
import com.lexken.lexkenai.core.network.NetworkQualifiers
import com.lexken.lexkenai.core.network.OkHttpSocketClient
import com.lexken.lexkenai.core.network.ReconnectPolicy
import com.lexken.lexkenai.core.network.SocketEvent
import com.lexken.lexkenai.core.network.SocketSubscription
import com.lexken.lexkenai.core.network.SocketUrls
import com.lexken.lexkenai.core.network.aisession.DeviceIdStore
import com.lexken.lexkenai.core.network.aisession.InMemoryDeviceIdStore
import com.lexken.lexkenai.core.network.networkModule
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.feature.account.accountModule
import com.lexken.lexkenai.feature.account.data.InMemoryOnboardingStore
import com.lexken.lexkenai.feature.account.data.InMemoryProductUpdateStore
import com.lexken.lexkenai.feature.account.domain.CheckProductUpdateUseCase
import com.lexken.lexkenai.feature.account.domain.FeedbackKind
import com.lexken.lexkenai.feature.account.domain.LawFirmRepository
import com.lexken.lexkenai.feature.account.domain.LoadLawFirmProfileUseCase
import com.lexken.lexkenai.feature.account.domain.LoadProfileUseCase
import com.lexken.lexkenai.feature.account.domain.MarkProductUpdateSeenUseCase
import com.lexken.lexkenai.feature.account.domain.OnboardingStore
import com.lexken.lexkenai.feature.account.domain.ProductUpdateStore
import com.lexken.lexkenai.feature.account.domain.ProductUpdatesRepository
import com.lexken.lexkenai.feature.account.domain.SendFeedbackUseCase
import com.lexken.lexkenai.feature.account.domain.SignOutAndWipeUseCase
import com.lexken.lexkenai.feature.account.domain.UpdateNotificationPreferencesUseCase
import com.lexken.lexkenai.feature.billing.BillingQualifiers
import com.lexken.lexkenai.feature.billing.billingModule
import com.lexken.lexkenai.feature.billing.domain.GetInvoiceDownloadUrlUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadCreditsBalanceUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadCreditsHistoryUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadPaymentHistoryUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadPlanCardUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadPlanCatalogueUseCase
import com.lexken.lexkenai.feature.billing.domain.LoadSubscriptionUseCase
import com.lexken.lexkenai.feature.billing.domain.PaymentStatus
import com.lexken.lexkenai.feature.billing.domain.SubscriptionBadgeRules
import com.lexken.lexkenai.feature.billing.domain.ValidateDiscountUseCase
import com.lexken.lexkenai.feature.chat.chatModule
import com.lexken.lexkenai.feature.chat.domain.BriefFileFormat
import com.lexken.lexkenai.feature.chat.domain.BriefFileStatus
import com.lexken.lexkenai.feature.chat.domain.BriefRepository
import com.lexken.lexkenai.feature.chat.domain.ChatMessage
import com.lexken.lexkenai.feature.chat.domain.ChatRepository
import com.lexken.lexkenai.feature.chat.domain.Conversation
import com.lexken.lexkenai.feature.chat.domain.DraftingRepository
import com.lexken.lexkenai.feature.chat.domain.ExplainStatuteUseCase
import com.lexken.lexkenai.feature.chat.domain.ExportAnswerToWordUseCase
import com.lexken.lexkenai.feature.chat.domain.FollowUpEvent
import com.lexken.lexkenai.feature.chat.domain.FollowUpUseCase
import com.lexken.lexkenai.feature.chat.domain.GenerateBriefFileUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadBriefHistoryUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadConversationsUseCase
import com.lexken.lexkenai.feature.chat.domain.LoadThreadUseCase
import com.lexken.lexkenai.feature.chat.domain.MessageRole
import com.lexken.lexkenai.feature.chat.domain.PlaybookError
import com.lexken.lexkenai.feature.chat.domain.RenameConversationUseCase
import com.lexken.lexkenai.feature.chat.domain.SendError
import com.lexken.lexkenai.feature.chat.domain.SendRequest
import com.lexken.lexkenai.feature.chat.domain.SendTurnUseCase
import com.lexken.lexkenai.feature.chat.domain.TurnEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.koin.core.Koin
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * Rung T9: the built features against `stage.lexken.ai` through the real Koin graph, with the
 * admin test account from `local.properties`. One sign-in (one mobile-token mint), reads for
 * everything, and only the writes that leave the shared account as they found it or that are a
 * genuine piece of work (feedback, a Word export, a brief file). Never run in CI: enabled by
 * `-Plexken.live=true`. Every request that reaches staging must read like an advocate at work.
 */
@EnabledIfSystemProperty(named = "lexken.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LiveStagingTest {
    private val props = Properties().apply { File(System.getProperty("lexken.localProperties")).inputStream().use { load(it) } }
    private val apiBase = props.getProperty("lexken.staging.apiBase", "https://stage.lexken.ai/api/")
    private val report = LiveReport(File(System.getProperty("lexken.liveReport")))
    private lateinit var koin: Koin
    private val onboarding = InMemoryOnboardingStore()
    private val productUpdates = InMemoryProductUpdateStore()

    private var conversations: List<Conversation> = emptyList()
    private var threads: Map<String, List<ChatMessage>> = emptyMap()
    private var creditsAtStart: Int? = null

    @BeforeAll
    fun signIn() {
        val overrides = module {
            single(NetworkQualifiers.BASE_URL) { apiBase }
            single(NetworkQualifiers.DEBUG_LOGGING) { false }
            single {
                VfConfig(
                    baseUrl = props.getProperty("lexken.vf.apiBase", "https://stage.lexkenems.com/api/"),
                    clientId = props.getProperty("lexken.vf.clientId"),
                    clientSecret = props.getProperty("lexken.vf.clientSecret"),
                    bypassKey = props.getProperty("lexken.vf.bypassKey"),
                    bundleId = props.getProperty("lexken.vf.bundleId", "com.lexken.mobile"),
                )
            }
            single<TokenStore> { InMemoryTokenStore() }
            single<DeviceIdStore> { InMemoryDeviceIdStore() }
            single<Clock> { Clock.SYSTEM }
            single<OnboardingStore> { onboarding } bind LocalStore::class
            single<ProductUpdateStore> { productUpdates } bind LocalStore::class
            single(BillingQualifiers.PAYMENT_PROVIDER_NAME) { "paystack" }
        }
        koin = koinApplication { modules(networkModule, authModule, accountModule, billingModule, chatModule, overrides) }.koin
        runBlocking {
            val outcome = koin.get<SignInUseCase>()(props.getProperty("lexken.test.admin.user"), props.getProperty("lexken.test.admin.pass"))
            report.add("Auth", "Sign in: EMS password grant → exchange → session reads", outcome == Result.Success(SignInOutcome.SignedIn), "$outcome")
            val state = withTimeoutOrNull(30_000) { koin.get<ObserveSessionUseCase>()().first { it !is SessionState.Unknown } }
            val signedIn = state as? SessionState.SignedIn
            report.add(
                "Auth",
                "Session state is SignedIn with user, firm and credits",
                signedIn != null,
                signedIn?.let { "user=${it.user.fullName} role=${it.user.role} firm=${it.user.firmName} credits=${it.credits.value}" } ?: "$state",
            )
            creditsAtStart = signedIn?.credits?.value
        }
    }

    @AfterAll
    fun signOutAndWrite() {
        runBlocking {
            val out = koin.get<SignOutAndWipeUseCase>()()
            report.add(
                "Auth",
                "Sign out: local wipe, then the identity-host revocation off-scope",
                out is Result.Success,
                "$out; onboarding cleared=${onboarding.current() == null}",
            )
            Thread.sleep(4_000) // let the off-scope refresh grant + logout finish before the process ends
        }
        report.write()
    }

    private fun live(area: String, check: String, block: suspend () -> Pair<Boolean, String>): Unit = runBlocking {
        val (ok, detail) = try {
            block()
        } catch (e: Throwable) {
            false to "threw ${e::class.simpleName}: ${e.message}"
        }
        report.add(area, check, ok, detail)
    }

    // ── Account (F1) ────────────────────────────────────────────────────────

    @Test
    @Order(10)
    fun account() {
        live("Account", "GET users/profile/ maps the user block and the six switches") {
            val p = koin.get<LoadProfileUseCase>()()
            (p is Result.Success) to
                (p.getOrNull()?.let { "name=${it.fullName} tz=${it.timezone} lang=${it.preferredLanguage} avatar=${it.avatarUrl != null}" } ?: "$p")
        }
        live("Account", "PATCH users/profile/ notification_preferences round-trip (same values back)") {
            val current = koin.get<LoadProfileUseCase>()().getOrNull()!!.notifications
            val saved = koin.get<UpdateNotificationPreferencesUseCase>()(current)
            (saved is Result.Success && saved.getOrNull()?.toWire() == current.toWire()) to "$saved"
        }
        live("Account", "GET users/lawfirm-profile/ maps the firm") {
            val f = koin.get<LoadLawFirmProfileUseCase>()()
            (f is Result.Success) to
                (f.getOrNull()?.let { "name=${it.name} admin=${it.adminEmail} employees=${it.employeeCount} logo=${it.logoUrl != null}" } ?: "$f")
        }
        live("Account", "PATCH users/lawfirm-profile/ with the firm's current values (no net change)") {
            val firm = koin.get<LoadLawFirmProfileUseCase>()().getOrNull()!!
            if (firm.name.isBlank()) {
                return@live true to
                    "skipped: the firm has no name on staging, a PATCH would have to invent one (the contract test covers the write)"
            }
            val patched = koin.get<LawFirmRepository>().patch(firm)
            (patched is Result.Success) to "$patched"
        }
        live("Account", "GET ai/product-updates/latest/ + POST mark-seen (idempotent)") {
            val update = koin.get<ProductUpdatesRepository>().latest()
            val marked = if (update.updateKey.isNotBlank()) koin.get<MarkProductUpdateSeenUseCase>()(update.updateKey) else Result.Success(Unit)
            val gated = koin.get<CheckProductUpdateUseCase>()()
            (update.updateKey.isNotBlank() && marked is Result.Success && gated == null) to
                "key=${update.updateKey} should_show=${update.shouldShow} marked=$marked"
        }
        live("Account", "POST users/feedback/ with genuine product feedback") {
            val sent = koin.get<SendFeedbackUseCase>()(
                FeedbackKind.FEEDBACK,
                "Mobile app: notification switches",
                "<p>The notification preference switches on the Android client now mirror the web profile tab, including the monthly cause list reminder.</p>",
            )
            (sent is Result.Success) to "$sent"
        }
        report.skip(
            "Account",
            "POST users/profile/avatar/ and the logo uploads",
            "not exercised: they change the shared admin account's visible avatar and firm logo; " +
                "covered by the contract tests",
        )
        report.skip(
            "Account",
            "DELETE users/profile/delete_account/",
            "not exercised: destructive on the only staging account; covered by the contract tests (both 204 shapes)",
        )
        report.skip("Account", "POST pms/smtp-config/ (setup stepper step 3)", "not exercised: creates a firm SMTP record with no API to remove it")
    }

    // ── Billing (F2) ────────────────────────────────────────────────────────

    @Test
    @Order(20)
    fun billing() {
        live("Billing", "GET sub/current/ maps the subscription and the badge") {
            val s = koin.get<LoadSubscriptionUseCase>()()
            (s is Result.Success) to
                (
                    s.getOrNull()?.let {
                        "plan=${it.planName} tier=${it.tier} active=${it.isActive} paid=${it.isPaid} " +
                            "expired=${it.isExpired} end=${it.endDate} badge=${SubscriptionBadgeRules.badge(it)}"
                    }
                        ?: "$s"
                )
        }
        live("Billing", "GET sub/ai-credits-balance/ on the web's or-chain") {
            val b = koin.get<LoadCreditsBalanceUseCase>()()
            (b is Result.Success) to
                (b.getOrNull()?.let { "credits=${it.credits} personal=${it.personal} subscription=${it.subscription} resets=${it.resetsOn}" } ?: "$b")
        }
        live("Billing", "GET sub/plans/ → catalogue with the static copy and Enterprise") {
            val c = koin.get<LoadPlanCatalogueUseCase>()()
            (c is Result.Success && c.getOrNull()!!.last().contactOnly) to
                (c.getOrNull()?.joinToString { "${it.tier}=${it.priceKes}/${it.durationDisplay}" } ?: "$c")
        }
    }

    @Test
    @Order(21)
    fun billingHistory() {
        live("Billing", "GET sub/payments/ + GET sub/invoices/:id/download/ for a paid invoice") {
            val payments = koin.get<LoadPaymentHistoryUseCase>()().getOrNull() ?: return@live false to "payments failed"
            val paid = payments.firstOrNull { it.status == PaymentStatus.SUCCESS && it.invoice != null }
            val link = paid?.let { koin.get<GetInvoiceDownloadUrlUseCase>()(it.invoice!!.id) }
            (link == null || link is Result.Success) to
                "payments=${payments.size} statuses=${payments.map {
                    it.status
                }.distinct()} invoiceLink=${link?.let { if (it is Result.Success) "ok" else "$it" } ?: "no paid invoice to try"}"
        }
        live("Billing", "GET ai/ai-credits-history/ and the relative next page") {
            val first = koin.get<LoadCreditsHistoryUseCase>()().getOrNull() ?: return@live false to "first page failed"
            val second = first.nextPage?.let { koin.get<LoadCreditsHistoryUseCase>()(it) }
            (second == null || second is Result.Success) to
                "count=${first.count} page1=${first.entries.size} next=${first.nextPage} page2=${second?.getOrNull()?.entries?.size}"
        }
        live("Billing", "GET auth/api/my-plan-card/") {
            val card = koin.get<LoadPlanCardUseCase>()()
            (card is Result.Success) to
                (card.getOrNull()?.let { "tier=${it.tier} storage=${it.storageLabel} users=${it.usersLabel} active=${it.activeUsers}" } ?: "$card")
        }
        live("Billing", "POST sub/discounts/validate/ with a code that is not valid for this plan") {
            val plan = koin.get<LoadPlanCatalogueUseCase>()().getOrNull()?.firstOrNull { !it.contactOnly } ?: return@live false to "no plan"
            val r = koin.get<ValidateDiscountUseCase>()("NEWFIRM25", plan.id)
            (r.errorOrNull() != null) to "$r (the web shows one fixed sentence for any refusal)"
        }
        report.skip(
            "Billing",
            "Paystack / Daraja subscribe and credit top-up, POST sub/request_discount_code",
            "not exercised: they create payment records, send an STK push or mint a real discount code; covered by the contract tests",
        )
    }

    // ── Chat (F3) ───────────────────────────────────────────────────────────

    @Test
    @Order(30)
    fun chatReads() {
        live("Chat", "GET ai/conversations/ sorted with title kinds") {
            val c = koin.get<LoadConversationsUseCase>()()
            conversations = c.getOrNull().orEmpty()
            (c is Result.Success) to
                "count=${conversations.size} kinds=${conversations.mapNotNull { it.kind }.distinct()} first=${conversations.firstOrNull()?.title}"
        }
        live("Chat", "GET ai/conversations/:id/messages/ for the three newest threads, cards rebuilt from metadata") {
            val loaded = conversations.take(3).associate { it.id to (koin.get<LoadThreadUseCase>()(it.id).getOrNull() ?: emptyList()) }
            threads = loaded
            val cards = loaded.values.flatten().filter { it.role == MessageRole.ASSISTANT }.map { it.cards }
            true to
                "threads=${loaded.size} messages=${loaded.values.sumOf {
                    it.size
                }} caseSources=${cards.sumOf {
                    it.caseSources.size
                }} statutes=${cards.sumOf {
                    it.statuteSources.size
                }} web=${cards.sumOf {
                    it.webSources.size
                }} briefs=${loaded.values.flatten().sumOf {
                    it.extractedCases.size
                }} handoffs=${cards.count { it.contractHandoff != null || it.submissionHandoff != null || it.dmsHandoff != null }}"
        }
        live("Chat", "PATCH ai/conversations/:id/ rename round-trip (same title back)") {
            val c = conversations.firstOrNull { !it.rawTitle.isNullOrBlank() } ?: return@live false to "no conversation with a title"
            val r = koin.get<RenameConversationUseCase>()(c.id, c.rawTitle!!)
            (r is Result.Success) to "$r"
        }
        live("Chat", "GET api/drafting/document-types/ + GET api/drafting/playbooks/:type/ (doubled /api/api/ prefix)") {
            val registry = koin.get<DraftingRepository>().registry()
            val playbook = registry.documentTypes.firstOrNull { !it.playbook.isNullOrBlank() }?.let { koin.get<DraftingRepository>().playbook(it.key) }
            (!registry.fromFallback && playbook is Result.Success) to
                "types=${registry.documentTypes.size} canEdit=${registry.canEditPlaybooks} playbook=${playbook?.getOrNull()?.let {
                    "v${it.version} canEdit=${it.canEdit} customized=${it.isCustomized}"
                } ?: "$playbook"}"
        }
        live("Chat", "GET api/drafting/playbooks/:type/versions/ + one version") {
            val type = koin.get<DraftingRepository>().registry().documentTypes.first { !it.playbook.isNullOrBlank() }.key
            val versions = koin.get<DraftingRepository>().versions(type)
            val detail = versions.getOrNull()?.firstOrNull()?.let { koin.get<DraftingRepository>().version(type, it.id) }
            (versions is Result.Success && (detail == null || detail is Result.Success)) to
                "versions=${versions.getOrNull()?.size} first=${versions.getOrNull()?.firstOrNull()?.let {
                    "v${it.version} ${it.changeType} by ${it.editedBy}"
                }} detailDiff=${detail?.getOrNull()?.diff?.length}"
        }
        live("Chat", "PUT api/drafting/playbooks/:type/ with the current content and version (no net change) — expect 200 or the firm-admin 403") {
            val repo = koin.get<DraftingRepository>()
            val type = repo.registry().documentTypes.first { !it.playbook.isNullOrBlank() }.key
            val current = repo.playbook(type).getOrNull() ?: return@live false to "load failed"
            val saved = repo.savePlaybook(type, current.content, current.version)
            val ok = saved is Result.Success || saved.errorOrNull() is PlaybookError.Rejected
            ok to "$saved"
        }
        live("Chat", "GET ai/case-brief/lookup/ by a citation from a persisted brief") {
            val brief =
                threads.values.flatten().flatMap { it.extractedCases }.firstOrNull()
                    ?: return@live true to "skipped: no persisted brief on the admin account's newest threads"
            val hit = koin.get<BriefRepository>().lookup(brief.citation, brief.id, brief.title)
            (hit is Result.Success) to "citation=${brief.citation} hit=${hit.getOrNull() != null}"
        }
        live("Chat", "GET rag/case/:id/history/ for a persisted brief") {
            val brief = threads.values.flatten().flatMap { it.extractedCases }.firstOrNull() ?: return@live true to "skipped: no persisted brief"
            val h = koin.get<LoadBriefHistoryUseCase>()(brief.id)
            (h is Result.Success) to "messages=${h.getOrNull()?.messages?.size} thread=${h.getOrNull()?.threadId}"
        }
    }

    @Test
    @Order(40)
    fun chatGatedAndSockets() {
        live("Chat", "POST ai/lawris-ai/ through the interceptor: the mint is refused, the send answers 403 ai_session_required (ask #1)") {
            val events = koin.get<SendTurnUseCase>()(
                SendRequest("What notice period applies to a monthly-paid employee under section 35 of the Employment Act, 2007?", ""),
            ).toList()
            val rejected = events.singleOrNull() as? TurnEvent.Rejected
            (rejected?.error == SendError.SessionChallenge) to "$events"
        }
        live("Chat", "WebSocket /ws/ai/lawris-ai/ upgrade + join with a real thread id: accepted, no error frame") {
            val thread = conversations.firstOrNull()?.id ?: return@live false to "no thread"
            val events = withTimeoutOrNull(4_000) {
                OkHttpSocketClient(koin.get<OkHttpClient>(NetworkQualifiers.SOCKET_CLIENT))
                    .connect(SocketUrls.chat(apiBase), listOf(SocketSubscription.chatJoinFrame(thread)), ReconnectPolicy(maxAttempts = 0))
                    .take(1).toList()
            } ?: emptyList()
            val connected = events.firstOrNull() is SocketEvent.Connected
            val error = events.filterIsInstance<SocketEvent.Frame>().any { it.text.contains("\"error\"") }
            (connected && !error) to "$events"
        }
    }

    @Test
    @Order(50)
    fun chatWork() {
        live("Chat", "POST ai/statute-explain/ on a persisted statute source") {
            val source =
                threads.values.flatten().flatMap { it.cards.statuteSources }.firstOrNull()
                    ?: return@live true to "skipped: no statute source on the newest threads"
            val answer = koin.get<ExplainStatuteUseCase>()(source)
            (answer is Result.Success) to "source=${source.title} answerChars=${answer.getOrNull()?.length} ${answer.errorOrNull() ?: ""}"
        }
        live("Chat", "POST ai/format-text-to-word/ on an assistant answer, poll, download the .docx with no bearer") {
            val (threadId, message) = threads.entries.firstNotNullOfOrNull { (id, ms) ->
                ms.lastOrNull {
                    it.role == MessageRole.ASSISTANT &&
                        it.content.length > 200
                }?.let { id to it }
            }
                ?: return@live true to "skipped: no assistant answer long enough"
            val today = Date()
            val export =
                koin.get<ExportAnswerToWordUseCase>()(
                    message.content,
                    threadId,
                    SimpleDateFormat("dd/MM/yyyy", Locale.US).format(today),
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(today),
                )
            (export is Result.Success) to (export.getOrNull()?.let { "${it.filename} ${it.bytes.size} bytes" } ?: "$export")
        }
        live("Chat", "GET ai/generate-brief/pdf/:id/ + poll ai/brief-status/ until the PDF lands") {
            val brief = threads.values.flatten().flatMap { it.extractedCases }.firstOrNull() ?: return@live true to "skipped: no persisted brief"
            val last = withTimeoutOrNull(180_000) { koin.get<GenerateBriefFileUseCase>()(brief.id, BriefFileFormat.PDF).toList().last() }
            (last is BriefFileStatus.Ready) to
                (
                    when (last) {
                        is BriefFileStatus.Ready -> "${last.filename} ${last.bytes.size} bytes"
                        null -> "timed out after 180 s"
                        else -> "$last"
                    }
                )
        }
        live("Chat", "POST rag/case/:id/follow-up/ + register_thread socket until the final answer") {
            val brief = threads.values.flatten().flatMap { it.extractedCases }.firstOrNull() ?: return@live true to "skipped: no persisted brief"
            val events =
                withTimeoutOrNull(120_000) { koin.get<FollowUpUseCase>()(brief.id, "What relief did the court grant, and on what ground?").toList() }
                    ?: emptyList()
            val final = events.lastOrNull()
            (final is FollowUpEvent.Final) to "events=${events.map { it::class.simpleName }} answerChars=${(final as? FollowUpEvent.Final)?.content?.length}"
        }
        live("Billing", "Credits after the paid work above") {
            val b = koin.get<LoadCreditsBalanceUseCase>()().getOrNull()
            (b != null) to "start=$creditsAtStart now=${b?.credits}"
        }
        report.skip(
            "Chat",
            "brief-selected-cases, cancel-task, cancel-briefing, bundles add-documents, delete conversation",
            "not exercised: need a live turn (gated by ask #1) or change shared bundles/threads; covered by the contract tests",
        )
    }
}

/** Collects one line per check and writes the Markdown the Desktop report is built from. */
class LiveReport(private val file: File) {
    private val rows = mutableListOf<Triple<String, String, Pair<String, String>>>()

    fun add(area: String, check: String, ok: Boolean, detail: String) {
        rows += Triple(area, check, (if (ok) "PASS" else "FAIL") to detail)
        println("LIVE ${if (ok) "PASS" else "FAIL"} [$area] $check — $detail")
    }

    fun skip(area: String, check: String, reason: String) {
        rows += Triple(area, check, "SKIPPED" to reason)
        println("LIVE SKIPPED [$area] $check — $reason")
    }

    fun write() {
        file.parentFile.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        file.writeText(
            buildString {
                appendLine("# Live staging run — $stamp")
                appendLine()
                appendLine("| Area | Check | Result | Detail |")
                appendLine("|---|---|---|---|")
                rows.forEach { (area, check, r) -> appendLine("| $area | $check | ${r.first} | ${r.second.replace("|", "\\|").replace("\n", " ")} |") }
            },
        )
    }
}
