package com.lexken.lexkenai.feature.account.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.errorOrNull
import com.lexken.lexkenai.core.result.getOrNull
import com.lexken.lexkenai.core.testing.ContractServer
import com.lexken.lexkenai.core.testing.ContractServer.Companion.bodyText
import com.lexken.lexkenai.core.testing.ContractServer.Companion.jsonBody
import com.lexken.lexkenai.core.testing.ContractServer.Companion.multipartParts
import com.lexken.lexkenai.core.testing.ContractServer.Companion.text
import com.lexken.lexkenai.core.testing.ContractServer.Host
import com.lexken.lexkenai.feature.account.domain.DeleteAccountError
import com.lexken.lexkenai.feature.account.domain.FeedbackKind
import com.lexken.lexkenai.feature.account.domain.LawFirmError
import com.lexken.lexkenai.feature.account.domain.LawFirmProfile
import com.lexken.lexkenai.feature.account.domain.NotificationPref
import com.lexken.lexkenai.feature.account.domain.NotificationPreferences
import com.lexken.lexkenai.feature.account.domain.ProductUpdate
import com.lexken.lexkenai.feature.account.domain.SmtpSettings
import com.lexken.lexkenai.feature.account.domain.SupportError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/** T2 for F1: every account request row of the auth appendix, on the wire against the contract server. */
class AccountContractTest {
    private val server = ContractServer()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
    private lateinit var retrofit: Retrofit

    @BeforeEach
    fun setUp() {
        server.start()
        retrofit = Retrofit.Builder().baseUrl(server.lawrisBaseUrl).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun profiles() = RemoteProfileRepository(retrofit.create())
    private fun lawFirms() = RemoteLawFirmRepository(retrofit.create(), retrofit.create(), json)
    private fun support() = RemoteSupportRepository(retrofit.create(), json)
    private fun account() = RemoteAccountRepository(retrofit.create(), json)
    private fun updates() = RemoteProductUpdatesRepository(retrofit.create())

    private val png = FileInput("wanjiru.png", "image/png", "png-bytes".toByteArray())

    @Test
    fun `profile load reads the user block, the switches and the locale auth#32`() = runTest {
        server.serve("users.profile")

        val profile = profiles().profile().getOrNull()!!

        assertThat(server.lastRequest(Host.LAWRIS, "GET", "/users/profile/").url.encodedPath).isEqualTo("/api/users/profile/")
        assertThat(profile.fullName).isEqualTo("Tier One")
        assertThat(profile.email).isEqualTo("tier1@lexken.test")
        assertThat(profile.phone).isEqualTo("+254712345678")
        assertThat(profile.timezone).isEqualTo("Africa/Nairobi")
        assertThat(profile.preferredLanguage).isEqualTo("en")
        assertThat(profile.notifications.isOn(NotificationPref.SMS_ALERTS)).isFalse()
        assertThat(profile.notifications.isOn(NotificationPref.EMAIL_UPDATES)).isTrue()
        assertThat(profile.avatarUrl).isEqualTo("https://stage.lexken.ai/media/avatars/41.png")
    }

    @Test
    fun `avatar upload posts one multipart part named avatar and answers the new url auth#33`() = runTest {
        server.serve("users.profile.avatar")

        val url = profiles().uploadAvatar(png).getOrNull()

        val parts = server.lastRequest(Host.LAWRIS, "POST", "/users/profile/avatar/").multipartParts()
        assertThat(parts.map { it.name }).containsExactly("avatar")
        assertThat(parts[0].filename).isEqualTo("wanjiru.png")
        assertThat(parts[0].contentType).isEqualTo("image/png")
        assertThat(parts[0].text).isEqualTo("png-bytes")
        assertThat(url).isEqualTo("https://stage.lexken.ai/media/avatars/user_41/wanjiru-profile.png")
    }

    @Test
    fun `notification preferences PATCH the full six-key object and read back the stored set auth#34`() = runTest {
        server.serve("users.profile.patch")

        val saved = profiles().updateNotificationPreferences(NotificationPreferences.DEFAULTS.with(NotificationPref.SMS_ALERTS, true)).getOrNull()!!

        val body = server.lastRequest(Host.LAWRIS, "PATCH", "/users/profile/").jsonBody().jsonObject["notification_preferences"]!!.jsonObject
        assertThat(
            body.keys,
        ).isEqualTo(setOf("email_updates", "sms_alerts", "in_app_new_features", "promotional_emails", "daily_case_reminders", "monthly_cause_list"))
        assertThat(body["sms_alerts"].toString()).isEqualTo("true")
        assertThat(saved.isOn(NotificationPref.PROMOTIONAL_EMAILS)).isFalse()
        assertThat(saved.isOn(NotificationPref.SMS_ALERTS)).isTrue()
    }

    @Test
    fun `law-firm profile load maps every field the tab shows auth#35`() = runTest {
        server.serve("users.lawfirm-profile")

        val firm = lawFirms().profile().getOrNull()!!

        assertThat(firm.name).isEqualTo("Tier One Advocates")
        assertThat(firm.adminEmail).isEqualTo("tier1@lexken.test")
        assertThat(firm.employeeCount).isEqualTo(3)
        assertThat(firm.address).isEqualTo("Upper Hill, Nairobi")
        assertThat(firm.logoUrl).isNull()
        assertThat(firm.technicalDetails).isEqualTo("{}")
    }

    @Test
    fun `the logo-only save is a multipart PATCH with the part named logo auth#36`() = runTest {
        server.serve("users.lawfirm-profile.updated")

        val firm = lawFirms().uploadLogo(png).getOrNull()!!

        val parts = server.lastRequest(Host.LAWRIS, "PATCH", "/users/lawfirm-profile/").multipartParts()
        assertThat(parts.map { it.name }).containsExactly("logo")
        assertThat(firm.logoUrl).isEqualTo("https://stage.lexken.ai/media/lawfirm_logos/mwangi-otieno.png")
    }

    @Test
    fun `the profile save is JSON without a logo and multipart with one, objects stringified auth#37`() = runTest {
        server.serve("users.lawfirm-profile.saved", "users.lawfirm-profile.logo")
        val profile = LawFirmProfile(
            name = "Tier One & Partners",
            adminName = "Tier One",
            adminEmail = "tier1@lexken.test",
            adminTel = "+254712345678",
            employeeCount = 3,
            address = "Riverside Drive, Nairobi",
            technicalDetails = """{"practice_areas":["conveyancing"]}""",
        )

        val saved = lawFirms().save(profile, logo = null).getOrNull()!!
        val withLogo = lawFirms().save(profile, logo = png).getOrNull()!!

        val posts = server.requests(Host.LAWRIS, "POST", "/users/lawfirm-profile/")
        assertThat(posts).hasSize(2)
        val jsonBody = posts[0].jsonBody().jsonObject
        assertThat(jsonBody.keys).isEqualTo(setOf("lawfirm_name", "admin_name", "admin_email", "admin_tel", "address", "employee_count", "technical_details"))
        assertThat(jsonBody["technical_details"].toString()).isEqualTo("""{"practice_areas":["conveyancing"]}""")
        val parts = posts[1].multipartParts()
        assertThat(
            parts.map {
                it.name
            },
        ).containsExactly("lawfirm_name", "admin_name", "admin_email", "admin_tel", "address", "employee_count", "technical_details", "logo")
        assertThat(parts.text("technical_details")).isEqualTo("""{"practice_areas":["conveyancing"]}""")
        assertThat(saved.name).isEqualTo("Tier One & Partners")
        assertThat(withLogo.logoUrl).isEqualTo("https://stage.lexken.ai/media/lawfirm_logos/tier-one.png")
    }

    @Test
    fun `the setup stepper posts logo and documents_logo, patches the profile, posts smtp, and maps detail refusals auth#38`() = runTest {
        server.serve("users.lawfirm-profile.logo", "users.lawfirm-profile.updated", "pms.smtp-config.created")
        server.serve(Host.LAWRIS, "PATCH", "/users/lawfirm-profile/", 400, """{"detail":"admin_email must be a valid email."}""")
        val firms = lawFirms()

        firms.uploadSetupLogos(loginLogo = png, pdfLogo = FileInput("letterhead.jpg", "image/jpeg", "jpg".toByteArray()))
        firms.uploadSetupLogos(loginLogo = null, pdfLogo = png)
        val patched = firms.patch(LawFirmProfile(name = "Mwangi & Otieno Advocates", adminName = "Wanjiru Mwangi", adminEmail = "wanjiru@mwangiotieno.co.ke"))
        val smtp = firms.saveSmtp(
            SmtpSettings(
                host = "smtp.mwangiotieno.co.ke",
                user = "notices@mwangiotieno.co.ke",
                password = "s3cret",
                fromEmail = "notices@mwangiotieno.co.ke",
                cc = listOf("records@mwangiotieno.co.ke"),
            ),
        )
        val refused = firms.patch(LawFirmProfile(name = "Mwangi & Otieno Advocates", adminEmail = "nope"))

        val logoPosts = server.requests(Host.LAWRIS, "POST", "/users/lawfirm-profile/")
        assertThat(logoPosts[0].multipartParts().map { it.name }).containsExactly("logo", "documents_logo")
        assertThat(logoPosts[1].multipartParts().map { it.name }).containsExactly("documents_logo")
        assertThat(server.requests(Host.LAWRIS, "PATCH", "/users/lawfirm-profile/")[0].jsonBody().jsonObject.keys)
            .isEqualTo(setOf("lawfirm_name", "admin_name", "admin_email"))
        assertThat(patched.getOrNull()!!.logoUrl).isEqualTo("https://stage.lexken.ai/media/lawfirm_logos/mwangi-otieno.png")
        val smtpBody = server.lastRequest(Host.LAWRIS, "POST", "/pms/smtp-config/").jsonBody().jsonObject
        assertThat(smtpBody.keys).isEqualTo(
            setOf(
                "email_host",
                "email_port",
                "email_use_tls",
                "email_use_ssl",
                "email_host_user",
                "email_host_password",
                "default_from_email",
                "cc_emails",
                "bcc_emails",
                "is_active",
            ),
        )
        assertThat(smtpBody["email_port"].toString()).isEqualTo("587")
        assertThat(smtp.getOrNull()).isEqualTo(Unit)
        assertThat(refused.errorOrNull()).isEqualTo(LawFirmError.Rejected("admin_email must be a valid email."))
    }

    @Test
    fun `delete account counts a bare 204, a 204 with a body, and maps a refusal sentence auth#39`() = runTest {
        server.serve("users.profile.delete_account", "users.profile.delete_account.with-body")
        server.serve(Host.LAWRIS, "DELETE", "/users/profile/delete_account/", 500, """{"error":"An error occurred during account deletion."}""")
        val repository = account()

        val bare = repository.deleteAccount()
        val withBody = repository.deleteAccount()
        val refused = repository.deleteAccount()

        assertThat(server.requests(Host.LAWRIS, "DELETE", "/users/profile/delete_account/")).hasSize(3)
        assertThat(bare).isEqualTo(Result.Success(Unit))
        assertThat(withBody).isEqualTo(Result.Success(Unit))
        assertThat(refused.errorOrNull()).isEqualTo(DeleteAccountError.Rejected("An error occurred during account deletion."))
    }

    @Test
    fun `feedback and bug reports post subject plus their own text key, and a 400 is a rejection auth#41`() = runTest {
        server.serve("users.feedback.200", "users.report-bug.200", "users.feedback.400-missing-subject")
        val repository = support()

        val feedback = repository.send(FeedbackKind.FEEDBACK, "Cause list export", "The Nakuru cause list PDF is blank today.")
        val bug = repository.send(FeedbackKind.BUG, "Crash on upload", "Uploading a 9 MB lease closes the app.")
        val refused = repository.send(FeedbackKind.FEEDBACK, "", "text")

        assertThat(server.lastRequest(Host.LAWRIS, "POST", "/users/feedback/").bodyText()).isEqualTo("""{"subject":"","feedback":"text"}""")
        assertThat(
            server.requests(Host.LAWRIS, "POST", "/users/feedback/")[0].bodyText(),
        ).isEqualTo("""{"subject":"Cause list export","feedback":"The Nakuru cause list PDF is blank today."}""")
        assertThat(
            server.lastRequest(Host.LAWRIS, "POST", "/users/report-bug/").bodyText(),
        ).isEqualTo("""{"subject":"Crash on upload","report":"Uploading a 9 MB lease closes the app."}""")
        assertThat(feedback).isEqualTo(Result.Success(Unit))
        assertThat(bug).isEqualTo(Result.Success(Unit))
        assertThat(refused.errorOrNull()!!).isInstanceOf(SupportError.Rejected::class)
    }

    @Test
    fun `the product update banner maps the payload, hides the seen shape, and fails open auth#59`() = runTest {
        server.serve("ai.product-updates.latest", "ai.product-updates.latest.seen")
        server.serve(Host.LAWRIS, "GET", "/ai/product-updates/latest/", 500, "<html>upstream</html>")
        val repository = updates()

        val fresh = repository.latest()
        val seen = repository.latest()
        val failed = repository.latest()

        assertThat(fresh.updateKey).isEqualTo("lexken_ai_reliability_research_pii_credits_update_2026_08")
        assertThat(fresh.shouldShow).isTrue()
        assertThat(fresh.title).isEqualTo("What's new in LexKen AI")
        assertThat(fresh.updates.map { it.icon }).containsExactly("database", "shield-check")
        assertThat(seen.shouldShow).isFalse()
        assertThat(seen.updates).hasSize(0)
        assertThat(failed).isEqualTo(ProductUpdate.NONE)
    }

    @Test
    fun `mark-seen posts the update key auth#60`() = runTest {
        server.serve("ai.product-updates.mark-seen")

        val marked = updates().markSeen("lexken_ai_reliability_research_pii_credits_update_2026_08")

        assertThat(server.lastRequest(Host.LAWRIS, "POST", "/ai/product-updates/mark-seen/").bodyText())
            .isEqualTo("""{"update_key":"lexken_ai_reliability_research_pii_credits_update_2026_08"}""")
        assertThat(marked).isEqualTo(Result.Success(Unit))
    }
}
