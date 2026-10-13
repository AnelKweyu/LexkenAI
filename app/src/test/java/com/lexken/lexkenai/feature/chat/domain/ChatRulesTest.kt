package com.lexken.lexkenai.feature.chat.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.model.FileInput
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.TimeZone

/** T1 for F3: one test per numbered client-side rule of the chat appendix (1–27, 33–35; 28–31 live in F1/F2). */
class ChatRulesTest {
    private fun file(name: String) = FileInput(name, null, ByteArray(1))

    @Test
    fun `r1 send needs a trimmed prompt, an idle UI and no request in flight`() {
        assertThat(ComposerRules.canSend("  Notice period?  ", uiBusy = false, requestInFlight = false)).isTrue()
        assertThat(ComposerRules.canSend("   ", uiBusy = false, requestInFlight = false)).isFalse()
        assertThat(ComposerRules.canSend("x", uiBusy = true, requestInFlight = false)).isFalse()
        assertThat(ComposerRules.canSend("x", uiBusy = false, requestInFlight = true)).isFalse()
        assertThat(ComposerRules.uiBusy(false, false, false, true, false)).isTrue()
        assertThat(ComposerRules.uiBusy(false, false, false, false, false)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["lease.PDF", "plaint.docx", "notes.md", "hearing.mp3", "scan.heic", "clip.webm", "table.csv", "brief.odt"])
    fun `r2 the twenty-three extensions pass, case-insensitively`(name: String) {
        assertThat(AttachmentRules.isAllowed(file(name))).isTrue()
    }

    @Test
    fun `r2 everything else is refused on extension alone, and the Word family is flagged as converted`() {
        assertThat(AttachmentRules.isAllowed(file("archive.zip"))).isFalse()
        assertThat(AttachmentRules.isAllowed(file("noextension"))).isFalse()
        assertThat(AttachmentRules.firstRejected(listOf(file("a.pdf"), file("b.exe"), file("c.zip")))).isEqualTo("b.exe")
        assertThat(AttachmentRules.firstRejected(listOf(file("a.pdf")))).isNull()
        assertThat(AttachmentRules.isConvertedToPdf(file("plaint.docx"))).isTrue()
        assertThat(AttachmentRules.isConvertedToPdf(file("plaint.pdf"))).isFalse()
        assertThat(AttachmentRules.EXTENSIONS.size).isEqualTo(23)
    }

    @Test
    fun `r3 modes are exclusive and turning the active one off falls back to General AI`() {
        val precedents = ModeRules.toggle(ComposerSettings(), ChatMode.INTERNAL_DB, on = true)
        assertThat(precedents.mode).isEqualTo(ChatMode.INTERNAL_DB)
        assertThat(ModeRules.toggle(precedents, ChatMode.DRAFTING_ASSISTANT, on = true).mode).isEqualTo(ChatMode.DRAFTING_ASSISTANT)
        assertThat(ModeRules.toggle(precedents, ChatMode.INTERNAL_DB, on = false).mode).isEqualTo(ChatMode.GENERAL_AI)
        assertThat(ModeRules.toggle(precedents, ChatMode.CASE_SEARCH, on = false)).isEqualTo(precedents)
        assertThat(ComposerSettings().useWebSources).isTrue()
        assertThat(ComposerSettings().precedentEngine).isEqualTo(PrecedentEngine.V3)
    }

    @Test
    fun `r4 research presets map to engine and effort pairs, unknown pairs display as fast`() {
        val pro = ResearchPresetRules.apply(ComposerSettings(), ResearchPreset.PRO)
        assertThat(pro.draftingEngine).isEqualTo(DraftingEngine.V3)
        assertThat(pro.draftingEffort).isEqualTo(DraftingEffort.VERY_HIGH)
        assertThat(ResearchPresetRules.presetOf(ComposerSettings())).isEqualTo(ResearchPreset.FAST)
        assertThat(ResearchPresetRules.presetOf(ResearchPresetRules.apply(ComposerSettings(), ResearchPreset.INSTANT))).isEqualTo(ResearchPreset.INSTANT)
        assertThat(
            ResearchPresetRules.presetOf(ComposerSettings(draftingEngine = DraftingEngine.V3, draftingEffort = DraftingEffort.HIGH)),
        ).isEqualTo(ResearchPreset.FAST)
        assertThat(ResearchPresetRules.precedentEngine(ResearchPreset.FAST)).isEqualTo(PrecedentEngine.V2)
        assertThat(ResearchPresetRules.precedentEngine(ResearchPreset.PRO)).isEqualTo(PrecedentEngine.V3)
    }

    @Test
    fun `r5 the pro-drafter controls appear only with Draft Assistant on very_high, the playbook button needs a playbook key`() {
        val pro = ResearchPresetRules.apply(ComposerSettings(mode = ChatMode.DRAFTING_ASSISTANT), ResearchPreset.PRO)
        assertThat(ProDrafterRules.isProDrafter(pro)).isTrue()
        assertThat(ProDrafterRules.isProDrafter(pro.copy(mode = ChatMode.GENERAL_AI))).isFalse()
        assertThat(ProDrafterRules.isProDrafter(pro.copy(draftingEffort = DraftingEffort.HIGH))).isFalse()
        val registry =
            DraftingRegistry(
                listOf(DocumentType("lease_agreement", "lease_agreement", "Lease", true), DocumentType("memo", null, "Memo", false)),
                listOf("docx"),
                "docx",
                true,
                false,
            )
        assertThat(ProDrafterRules.hasPlaybook(registry, "lease_agreement")).isTrue()
        assertThat(ProDrafterRules.hasPlaybook(registry, "memo")).isFalse()
    }

    @Test
    fun `r6 a playbook skill forces Draft Assistant on pro and resolves the document type by key, slug, then label`() {
        val registry =
            DraftingRegistry(
                listOf(
                    DocumentType("land_sale_agreement", "land_sale_agreement", "Land sale agreement", true),
                    DocumentType("lease_agreement", "lease_pb", "Lease agreement", true),
                ),
                listOf("docx"),
                "docx",
                true,
                false,
            )
        val byKey = PlaybookSkillRules.load(ComposerSettings(), registry, PlaybookSkill("Anything", null, "lease_agreement"))
        assertThat(byKey.mode).isEqualTo(ChatMode.DRAFTING_ASSISTANT)
        assertThat(byKey.draftingEffort).isEqualTo(DraftingEffort.VERY_HIGH)
        assertThat(byKey.draftingDocumentType).isEqualTo("lease_agreement")
        assertThat(PlaybookSkillRules.documentTypeForSkill(registry, PlaybookSkill("x", "land-sale-agreement", null), "")).isEqualTo("land_sale_agreement")
        assertThat(PlaybookSkillRules.documentTypeForSkill(registry, PlaybookSkill("x", "lease-pb", null), "")).isEqualTo("lease_agreement")
        assertThat(PlaybookSkillRules.documentTypeForSkill(registry, PlaybookSkill("Lease Agreement", null, null), "")).isEqualTo("lease_agreement")
        assertThat(PlaybookSkillRules.documentTypeForSkill(registry, PlaybookSkill("Unknown", "nope", "nope"), "kept")).isEqualTo("kept")
    }

    @Test
    fun `r7 a welcome quick action activates its mode and may apply the fast preset`() {
        val pro = ResearchPresetRules.apply(ComposerSettings(), ResearchPreset.PRO)
        val quick = QuickActionRules.apply(pro, ChatMode.INTERNAL_DB, usesFastPreset = true)
        assertThat(quick.mode).isEqualTo(ChatMode.INTERNAL_DB)
        assertThat(ResearchPresetRules.presetOf(quick)).isEqualTo(ResearchPreset.FAST)
        assertThat(QuickActionRules.apply(pro, ChatMode.GENERAL_AI, usesFastPreset = false)).isEqualTo(pro)
    }

    @Test
    fun `r8 new chat resets everything, selection is ignored while busy, the send adopts the returned ids`() {
        val busy =
            ThreadState(
                activeThreadId = "t-1",
                backendThreadId = "t-1",
                currentTaskId = "task",
                settings = ComposerSettings(mode = ChatMode.INTERNAL_DB, useWebSources = false),
            )
        assertThat(ThreadLifecycleRules.newChat()).isEqualTo(ThreadState())
        assertThat(ThreadLifecycleRules.canSelect(busy, "t-2", loading = false, streaming = true)).isFalse()
        assertThat(ThreadLifecycleRules.canSelect(busy, "t-1", loading = false, streaming = false)).isFalse()
        assertThat(ThreadLifecycleRules.canSelect(busy, "t-2", loading = false, streaming = false)).isTrue()
        val selected = ThreadLifecycleRules.select(busy, "t-2")
        assertThat(selected.activeThreadId).isEqualTo("t-2")
        assertThat(selected.backendThreadId).isNull()
        assertThat(selected.settings.mode).isEqualTo(ChatMode.GENERAL_AI)
        val adopted = ThreadLifecycleRules.adopt(ThreadState(activeThreadId = "stale"), SendAccepted("server-1", "task-9", 202))
        assertThat(adopted.activeThreadId).isEqualTo("server-1")
        assertThat(adopted.backendThreadId).isEqualTo("server-1")
        assertThat(adopted.currentTaskId).isEqualTo("task-9")
        assertThat(ThreadLifecycleRules.threadIdForSend(ThreadState())).isEqualTo("")
        assertThat(ThreadLifecycleRules.threadIdForSend(adopted)).isEqualTo("server-1")
        assertThat(ThreadLifecycleRules.threadIdForSend(adopted.copy(settings = ComposerSettings(mode = ChatMode.CASE_SEARCH)))).isEqualTo("server-1")
    }

    @Test
    fun `r9 optimistic rows carry the web's ids and placeholders, and a retry re-adds only the assistant row`() {
        val rows = OptimisticMessages.rowsForSend(1_000L, "Notice period?", listOf("lease.pdf"), isRetry = false)
        assertThat(rows.map { it.id }).containsExactly("user-1000", "assistant-session-1000")
        assertThat(rows[0].attachmentNames).containsExactly("lease.pdf")
        assertThat(rows[1].progressSteps).containsExactly(OptimisticMessages.SESSION_PLACEHOLDER)
        assertThat(OptimisticMessages.rowsForSend(1_000L, "x", emptyList(), isRetry = true).map { it.id }).containsExactly("assistant-session-1000")
        val rag = OptimisticMessages.acceptedAssistant(2_000L, TurnFlow.RAG, "LexKen AI")
        assertThat(rag.id).isEqualTo("assistant-2000")
        assertThat(rag.content).isEqualTo("Processing query...")
        assertThat(rag.progressSteps).containsExactly("Initiating analysis...")
        assertThat(OptimisticMessages.acceptedAssistant(2_000L, TurnFlow.GENERAL, "LexKen AI").progressSteps).containsExactly("LexKen AI is thinking...")
        assertThat(OptimisticMessages.isTempAssistant("assistant-2000")).isTrue()
        assertThat(OptimisticMessages.isError("err-3")).isTrue()
    }

    @Test
    fun `r10 the thinking sentinel is dropped once a real progress line arrives, and lines dedupe`() {
        val steps = ThinkingSentinel.append(listOf("LexKen AI is thinking..."), "Searching statutes")
        assertThat(steps).containsExactly("Searching statutes")
        assertThat(ThinkingSentinel.append(steps, "Searching statutes")).containsExactly("Searching statutes")
        assertThat(ThinkingSentinel.append(emptyList(), "LexKen AI is thinking…")).containsExactly("LexKen AI is thinking…")
        assertThat(ThinkingSentinel.isSentinel("thinking about it")).isFalse()
    }

    @Test
    fun `r11 progress frames from another run or an older sequence are dropped`() {
        assertThat(ProgressOrdering.decide(null, null, "run-1", 1L)).isEqualTo(ProgressOrdering.Decision(true, "run-1", 1L))
        assertThat(ProgressOrdering.decide("run-1", 1L, "run-0", 9L).accept).isFalse()
        assertThat(ProgressOrdering.decide("run-1", 2L, "run-1", 2L).accept).isFalse()
        assertThat(ProgressOrdering.decide("run-1", 2L, "run-1", 3L)).isEqualTo(ProgressOrdering.Decision(true, "run-1", 3L))
        assertThat(ProgressOrdering.decide("run-1", 2L, null, null)).isEqualTo(ProgressOrdering.Decision(true, "run-1", 2L))
    }

    @Test
    fun `r12 retry removes a trailing error row before re-running`() {
        val messages = listOf(ChatMessage("user-1", MessageRole.USER, "q"), ChatMessage("err-2", MessageRole.ASSISTANT, "**Error:** x"))
        assertThat(RetryRules.messagesBeforeRetry(messages).map { it.id }).containsExactly("user-1")
        assertThat(RetryRules.messagesBeforeRetry(messages.take(1)).map { it.id }).containsExactly("user-1")
        assertThat(LastRequest.LoadMessages("t-1")).isInstanceOf(LastRequest::class)
    }

    @Test
    fun `r13 stop rewrites the last assistant row only while it is still a placeholder`() {
        val pending =
            listOf(
                ChatMessage("u", MessageRole.USER, "q"),
                ChatMessage("assistant-1", MessageRole.ASSISTANT, "Processing query...", isProcessing = true, progressSteps = listOf("x")),
            )
        val cancelled = CancelRules.afterCancel(pending).last()
        assertThat(cancelled.content).isEqualTo("Request cancelled by user.")
        assertThat(cancelled.isProcessing).isFalse()
        assertThat(cancelled.progressSteps).isEmpty()
        val streamed = pending.dropLast(1) + pending.last().copy(content = "Under section 35")
        assertThat(CancelRules.afterCancel(streamed).last().content).isEqualTo("Under section 35")
    }

    @Test
    fun `r14 two cases at most, five shown on the auto-brief card with the first two pre-selected, confirm needs a backend thread`() {
        assertThat(BriefSelectionRules.toggle(emptyList(), "A")).containsExactly("A")
        assertThat(BriefSelectionRules.toggle(listOf("A", "B"), "C")).containsExactly("A", "B")
        assertThat(BriefSelectionRules.toggle(listOf("A", "B"), "A")).containsExactly("B")
        val six = listOf("A", "B", "C", "D", "E", "F")
        assertThat(BriefSelectionRules.shown(six)).containsExactly("A", "B", "C", "D", "E")
        assertThat(BriefSelectionRules.preselected(six)).containsExactly("A", "B")
        assertThat(BriefSelectionRules.canConfirm(listOf("A"), "t-1")).isTrue()
        assertThat(BriefSelectionRules.canConfirm(listOf("A"), null)).isFalse()
        assertThat(BriefSelectionRules.canConfirm(emptyList(), "t-1")).isFalse()
        assertThat(BriefSelectionRules.timeout(null)).isEqualTo(300)
        assertThat(BriefSelectionRules.timeout(120)).isEqualTo(120)
    }

    @Test
    fun `r15 the busy key is case_id, then id, then citation, and a lookup hit needs a 200 with a summary`() {
        assertThat(SourceCardRules.busyKey("c-1", "i-1", "[2019] eKLR")).isEqualTo("c-1")
        assertThat(SourceCardRules.busyKey(null, "i-1", "[2019] eKLR")).isEqualTo("i-1")
        assertThat(SourceCardRules.busyKey("", null, "[2019] eKLR")).isEqualTo("[2019] eKLR")
        assertThat(SourceCardRules.isLookupHit(200, "The court held…")).isTrue()
        assertThat(SourceCardRules.isLookupHit(200, "")).isFalse()
        assertThat(SourceCardRules.isLookupHit(204, "x")).isFalse()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Karanja v Mwaura [2019] eKLR",
            "Kenya Airways Limited v Aviation & Allied Workers Union Kenya [2014] eKLR",
            "Michael Rabare Ramoya & 2 others v Stephen Okota Barasa [2020] KLR",
            "Republic v. Kenya National Examinations Council [2021] eKLR",
            "Odinga v IEBC [2017] eKLR",
            "Mwangi v Attorney General [2015]eKLR",
            "Nairobi City County v Kiambu County Government [2022] KLR",
            "Wanjiru v Mwangi [2018] eKLR",
            "Okiya Omtatah Okoiti v Attorney General [2020] eKLR",
            "Law Society of Kenya v Attorney General [2019] eKLR",
            "Samuel Kamau Macharia v Kenya Commercial Bank [2012] eKLR",
            "Standard Chartered Bank v Intercom Services Limited [2004] eKLR",
        ],
    )
    fun `r16 the citation chip regex matches twelve Kenyan citations`(citation: String) {
        assertThat(CitationChipRules.citations("$citation settles it.")).containsExactly(citation)
    }

    @Test
    fun `r16 the chip regex rejects text without a year bracket or without a v, and the chip's source is the citation thrice`() {
        // The web's lazy regex starts at the first capital letter, so a capitalised lead-in rides into the chip — kept as is.
        assertThat(
            CitationChipRules.citations("The holding in Karanja v Mwaura [2019] eKLR settles it."),
        ).containsExactly("The holding in Karanja v Mwaura [2019] eKLR")
        assertThat(CitationChipRules.citations("Karanja v Mwaura, decided in 2019, is on point.")).isEmpty()
        assertThat(CitationChipRules.citations("Employment Act [2007] eKLR")).isEmpty()
        assertThat(
            CitationChipRules.sourceJson("""Karanja v Mwaura [2019] eKLR"""),
        ).isEqualTo("""{"citation":"Karanja v Mwaura [2019] eKLR","name":"Karanja v Mwaura [2019] eKLR","case_id":"Karanja v Mwaura [2019] eKLR"}""")
    }

    @Test
    fun `r18 title prefixes become kinds and are stripped, blank titles fall back, the sidebar groups by day`() {
        assertThat(ConversationDisplayRules.kindOf("[db] Adverse possession")).isEqualTo(ConversationKind.INTERNAL_DB)
        assertThat(ConversationDisplayRules.kindOf("[Contract] Lease")).isEqualTo(ConversationKind.CONTRACT_ANALYSIS)
        assertThat(ConversationDisplayRules.kindOf("[Weird] x")).isNull()
        assertThat(ConversationDisplayRules.titleOf("[General AI]   Notice period", "t-1")).isEqualTo("Notice period")
        assertThat(ConversationDisplayRules.titleOf("[Weird] kept", "t-1")).isEqualTo("[Weird] kept")
        assertThat(ConversationDisplayRules.titleOf("  ", "t-1")).isEqualTo("Conversation t-1")
        val nairobi = TimeZone.getTimeZone("Africa/Nairobi")
        val now = 1_760_000_000_000L // 2025-10-09T08:53:20Z
        val day = 86_400_000L
        assertThat(ConversationDisplayRules.groupOf(now - 60_000L, now, nairobi)).isEqualTo(ConversationGroup.TODAY)
        assertThat(ConversationDisplayRules.groupOf(now - day, now, nairobi)).isEqualTo(ConversationGroup.YESTERDAY)
        assertThat(ConversationDisplayRules.groupOf(now - 5 * day, now, nairobi)).isEqualTo(ConversationGroup.PREVIOUS_7_DAYS)
        assertThat(ConversationDisplayRules.groupOf(now - 20 * day, now, nairobi)).isEqualTo(ConversationGroup.PREVIOUS_30_DAYS)
        assertThat(ConversationDisplayRules.groupOf(now - 45 * day, now, nairobi)).isEqualTo(ConversationGroup.OLDER)
        val c1 = Conversation("1", "[DB] a", "a", ConversationKind.INTERNAL_DB, null, now - day)
        val c2 = Conversation("2", "b", "b", null, null, now)
        assertThat(ConversationDisplayRules.sorted(listOf(c1, c2)).map { it.id }).containsExactly("2", "1")
        assertThat(
            ConversationDisplayRules.sections(listOf(c1, c2), now, nairobi).map {
                it.first
            },
        ).containsExactly(ConversationGroup.TODAY, ConversationGroup.YESTERDAY)
        assertThat(ConversationDisplayRules.matches(c2, "B")).isTrue()
        assertThat(ConversationDisplayRules.matches(c2, "2")).isTrue()
        assertThat(ConversationDisplayRules.matches(c2, "zzz")).isFalse()
    }

    @Test
    fun `r19 the generation confirm selects suggested, then required, then all, capped by the limit, and sends the synthesised prompt`() {
        val offer =
            DocumentBundleOffer(
                "suits:suit_2",
                "Breach of contract",
                listOf(BundleDocument("Demand Letter", true), BundleDocument("Plaint", true), BundleDocument("List of Witnesses")),
                suggestedDocuments = listOf("Demand Letter", "Plaint", "List of Witnesses"),
                defaultDocumentLimit = 2,
            )
        assertThat(GenerationConfirmRules.defaultSelection(offer)).containsExactly("Demand Letter", "Plaint")
        assertThat(
            GenerationConfirmRules.defaultSelection(offer.copy(suggestedDocuments = emptyList(), defaultDocumentLimit = null)),
        ).containsExactly("Demand Letter", "Plaint")
        assertThat(
            GenerationConfirmRules.defaultSelection(
                offer.copy(
                    suggestedDocuments = emptyList(),
                    documents = offer.documents.map {
                        it.copy(required = false)
                    },
                    defaultDocumentLimit = null,
                ),
            ),
        ).containsExactly("Demand Letter", "Plaint", "List of Witnesses")
        assertThat(GenerationConfirmRules.defaultSelection(offer.copy(defaultDocumentLimit = 0))).isEmpty()
        assertThat(GenerationConfirmRules.toggled(offer, listOf("Demand Letter", "Plaint"), "Demand Letter")).containsExactly("Demand Letter", "Plaint")
        assertThat(GenerationConfirmRules.toggled(offer, listOf("Demand Letter", "Plaint"), "List of Witnesses")).containsExactly("Demand Letter", "Plaint")
        assertThat(GenerationConfirmRules.confirmationPrompt(offer, listOf("Demand Letter", "Plaint")))
            .isEqualTo("Yes — please generate these documents for the Breach of contract bundle: Demand Letter, Plaint.")
        assertThat(GenerationConfirmRules.shouldRender(true, offer, null)).isTrue()
        assertThat(GenerationConfirmRules.shouldRender(false, offer, null)).isFalse()
        assertThat(GenerationConfirmRules.shouldRender(true, offer, DocumentGenerationCard(optionName = "x"))).isFalse()
    }

    @Test
    fun `r20 the bundle-addition card shows the proposed bundle, else current plus documents, only while pending`() {
        val offer =
            BundleAdditionOffer(
                "land-dispute",
                "Land dispute",
                listOf("Witness statement"),
                currentBundle = listOf("Plaint"),
                proposedBundle = listOf("Plaint", "Witness statement"),
            )
        assertThat(BundleAdditionRules.shownBundle(offer)).containsExactly("Plaint", "Witness statement")
        assertThat(BundleAdditionRules.shownBundle(offer.copy(proposedBundle = emptyList()))).containsExactly("Plaint", "Witness statement")
        assertThat(BundleAdditionRules.shouldRender(true, offer)).isTrue()
        assertThat(BundleAdditionRules.shouldRender(true, offer.copy(pending = false))).isFalse()
    }

    @Test
    fun `r21 the generation card's status maps the task vocabulary`() {
        assertThat(GenerationCardRules.status(null)).isEqualTo("unknown")
        assertThat(GenerationCardRules.status("RUNNING")).isEqualTo("running")
        assertThat(GenerationCardRules.status("SUCCESS")).isEqualTo("complete")
        assertThat(GenerationCardRules.status("PARTIAL_FAILURE")).isEqualTo("failed")
        assertThat(GenerationCardRules.status("CANCELLED")).isEqualTo("failed")
    }

    @Test
    fun `r23 a dms handoff auto-opens once per task, two tools share a window, and progress patches by stage`() {
        val card = ToolHandoffCard(HandoffTool.TENTH_LINING_WITH_PAGINATION, "started", taskId = "dms-1")
        assertThat(DmsHandoffRules.windowFor(card.tool)).isEqualTo(HandoffTool.TENTH_LINING)
        assertThat(DmsHandoffRules.shouldAutoOpen(card, emptySet())).isTrue()
        assertThat(DmsHandoffRules.shouldAutoOpen(card, setOf("dms-1"))).isFalse()
        assertThat(DmsHandoffRules.shouldAutoOpen(card.copy(tool = HandoffTool.CONTRACT_ANALYSIS), emptySet())).isFalse()
        assertThat(
            DmsHandoffRules.patch(card, "complete", null, "Done", null, "https://x/out.pdf"),
        ).isEqualTo(card.copy(status = "complete", progress = 100, outputUrl = "https://x/out.pdf", stage = "complete", message = "Done"))
        assertThat(DmsHandoffRules.patch(card, null, "FAILURE", "Broke", null, null).status).isEqualTo("error")
        assertThat(DmsHandoffRules.patch(card, "running", null, null, 40, null)).isEqualTo(card.copy(status = "started", stage = "running", progress = 40))
    }

    @Test
    fun `r24 open routes a dms tool to its window, the analyzer to its session without the full preset, contracts to their route`() {
        assertThat(HandoffOpenRules.open(ToolHandoffCard(HandoffTool.OCR, "complete", taskId = "t"))).isEqualTo(HandoffOpen.DmsTool(HandoffTool.OCR, "t"))
        assertThat(
            HandoffOpenRules.open(ToolHandoffCard(HandoffTool.SUBMISSION_ANALYZER, "complete", mode = "full", sessionId = "s-1")),
        ).isEqualTo(HandoffOpen.Analyzer("s-1", null))
        assertThat(
            HandoffOpenRules.open(ToolHandoffCard(HandoffTool.SUBMISSION_ANALYZER, "complete", mode = "authorities_only", threadId = "t-1")),
        ).isEqualTo(HandoffOpen.Analyzer("t-1", "authorities_only"))
        assertThat(
            HandoffOpenRules.open(ToolHandoffCard(HandoffTool.CONTRACT_ANALYSIS, "complete", route = "/contract-analysis/a-1")),
        ).isEqualTo(HandoffOpen.Route("/contract-analysis/a-1"))
        assertThat(
            HandoffOpenRules.open(ToolHandoffCard(HandoffTool.CONTRACT_ANALYSIS, "complete", analysisId = "a-2")),
        ).isEqualTo(HandoffOpen.Route("/contract-analysis/a-2"))
        assertThat(HandoffOpenRules.open(ToolHandoffCard(HandoffTool.UNKNOWN, "queued"))).isEqualTo(HandoffOpen.Nothing)
    }

    @Test
    fun `r25 the draft preview auto-opens only for the first draft of this session, never on history load`() {
        val card = DraftDocumentCard("Demand letter", "ready")
        assertThat(DraftPreviewRules.shouldAutoOpen(seenDraftThisSession = false, fromHistoryLoad = false, card = card)).isTrue()
        assertThat(DraftPreviewRules.shouldAutoOpen(seenDraftThisSession = true, fromHistoryLoad = false, card = card)).isFalse()
        assertThat(DraftPreviewRules.shouldAutoOpen(seenDraftThisSession = false, fromHistoryLoad = true, card = card)).isFalse()
        assertThat(DraftPreviewRules.shouldAutoOpen(false, false, null)).isFalse()
    }

    @Test
    fun `r26 a pro artifact always needs a signed url, fill needs can_replace and a template, media paths resolve on the host root`() {
        val artifact = DraftingArtifact("art-1", "Demand_letter.docx", "/api/drafting/artifacts/art-1/download/", 1L, "tmpl-1", "ready", 2, true, null)
        val card = DraftDocumentRules.fromArtifact(artifact)
        assertThat(card.needsSignedUrl).isTrue()
        assertThat(card.documentName).isEqualTo("Demand_letter.docx")
        assertThat(DraftDocumentRules.canFill(card)).isTrue()
        assertThat(DraftDocumentRules.canFill(card.copy(templateId = null))).isFalse()
        assertThat(
            DraftDocumentRules.absoluteMediaUrl("https://stage.lexken.ai", "/media/u/AI_DRAFTS/x.docx"),
        ).isEqualTo("https://stage.lexken.ai/media/u/AI_DRAFTS/x.docx")
        assertThat(DraftDocumentRules.absoluteMediaUrl("https://stage.lexken.ai", "https://cdn/x.docx")).isEqualTo("https://cdn/x.docx")
        assertThat(DraftDocumentRules.absoluteMediaUrl("https://stage.lexken.ai", " ")).isNull()
    }

    @Test
    fun `r33 deleting a message is a local filter and a user message copies back into the composer`() {
        val messages = listOf(ChatMessage("u-1", MessageRole.USER, "Notice period?"), ChatMessage("a-1", MessageRole.ASSISTANT, "x"))
        assertThat(MessageEditRules.withoutMessage(messages, "a-1").map { it.id }).containsExactly("u-1")
        assertThat(MessageEditRules.composerTextFor(messages[0])).isEqualTo("Notice period?")
        assertThat(MessageEditRules.composerTextFor(messages[1])).isNull()
    }

    @Test
    fun `r34 briefs dedupe by their id`() {
        val a = CaseBrief("c-1", "Karanja v Mwaura", null, null, null, null, null, null)
        assertThat(DedupeRules.appendBrief(listOf(a), a.copy(title = "dup"))).containsExactly(a)
        assertThat(DedupeRules.appendBrief(listOf(a), a.copy(id = "c-2"))).containsExactly(a, a.copy(id = "c-2"))
    }

    @Test
    fun `r35 the timers are the web's, and the export names follow the brand and date`() {
        assertThat(ChatTimers.EXPORT_POLL_MILLIS).isEqualTo(5_000L)
        assertThat(ChatTimers.EXPORT_MAX_ATTEMPTS).isEqualTo(30)
        assertThat(ChatTimers.BRIEF_FILE_POLL_MILLIS).isEqualTo(3_000L)
        assertThat(ChatTimers.LIST_REFETCH_AFTER_CLOSE_MILLIS).isEqualTo(1_000L)
        assertThat(ChatTimers.BRIEF_SOCKET_SAFETY_MILLIS).isEqualTo(300_000L)
        assertThat(ExportNamingRules.documentName("LexKen AI", "13/10/2026")).isEqualTo("LexKen AI Response - 13/10/2026")
        assertThat(ExportNamingRules.fileName("LexKen AI", "2026-10-13")).isEqualTo("lexken-ai-response-2026-10-13.docx")
        assertThat(
            ExportNamingRules.briefFileName("""attachment; filename="[Case Brief] Karanja v Mwaura.pdf"""", "c-1", BriefFileFormat.PDF),
        ).isEqualTo("[Case Brief] Karanja v Mwaura.pdf")
        assertThat(ExportNamingRules.briefFileName(null, "c-1", BriefFileFormat.DOCX)).isEqualTo("case_brief_c-1.docx")
    }

    @Test
    fun `r8 the send carries the web's sixteen form fields in order with the mode flags derived`() {
        val settings = ResearchPresetRules.apply(
            ComposerSettings(mode = ChatMode.DRAFTING_ASSISTANT, draftingDocumentType = "lease_agreement"),
            ResearchPreset.PRO,
        )
        val fields = SendRequest("Draft a lease", "t-1", settings).formFields()
        assertThat(fields.keys.toList()).containsExactly(
            "prompt",
            "thread_id",
            "case_search",
            "old_case_search",
            "drafting_assistant",
            "transcribe",
            "use_internal_db",
            "use_internal_db_version",
            "drafting_effort",
            "drafting_engine",
            "documents_per_run",
            "drafting_document_type",
            "drafting_output_format",
            "drafting_post_fill",
            "use_web_sources",
            "model_choice",
        )
        assertThat(fields["drafting_assistant"]).isEqualTo("true")
        assertThat(fields["use_internal_db"]).isEqualTo("false")
        assertThat(fields["drafting_effort"]).isEqualTo("very_high")
        assertThat(fields["drafting_engine"]).isEqualTo("v3")
        assertThat(fields["documents_per_run"]).isEqualTo("2")
        assertThat(fields["drafting_output_format"]).isEqualTo("docx")
        assertThat(fields["drafting_post_fill"]).isEqualTo("false")
        assertThat(fields["model_choice"]).isEqualTo("lawris-ai")
        assertThat(SendRequest("x", null).formFields()["thread_id"]).isEqualTo("")
        assertThat(SendRequest("x", null, ComposerSettings(mode = ChatMode.INTERNAL_DB)).flow).isEqualTo(TurnFlow.RAG)
        assertThat(SendRequest("x", null, ComposerSettings(mode = ChatMode.CASE_SEARCH)).flow).isEqualTo(TurnFlow.CASE_SEARCH)
    }
}
