package com.lexken.lexkenai.feature.account.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.lexken.lexkenai.core.model.FileInput
import org.junit.jupiter.api.Test

/** T1 for F1: one test per numbered rule of the auth appendix the account feature encodes (rules 17, 29, 34–39, 42). */
class AccountRulesTest {
    private fun file(name: String, type: String?, size: Int) = FileInput(name, type, ByteArray(size))

    @Test
    fun `r33 avatar is refused above 2 MB or outside jpeg, png and gif`() {
        assertThat(AvatarRules.validate(file("wanjiru.jpg", "image/jpeg", 2 * 1024 * 1024))).isNull()
        assertThat(AvatarRules.validate(file("wanjiru.png", "image/png", 2 * 1024 * 1024 + 1))).isEqualTo(AvatarProblem.TOO_LARGE)
        assertThat(AvatarRules.validate(file("wanjiru.webp", "image/webp", 10))).isEqualTo(AvatarProblem.TYPE)
        assertThat(AvatarRules.validate(file("wanjiru", null, 10))).isEqualTo(AvatarProblem.TYPE)
    }

    @Test
    fun `r36 the firm logo allows svg and up to 4 MB`() {
        assertThat(LogoRules.validate(file("logo.svg", "image/svg+xml", 4 * 1024 * 1024))).isNull()
        assertThat(LogoRules.validate(file("logo.png", "image/png", 4 * 1024 * 1024 + 1))).isEqualTo(LawFirmProblem.LOGO_TOO_LARGE)
        assertThat(LogoRules.validate(file("logo.pdf", "application/pdf", 10))).isEqualTo(LawFirmProblem.LOGO_TYPE)
    }

    @Test
    fun `r61 the setup stepper's logos take any image up to 5 MB`() {
        assertThat(SetupLogoRules.validate(file("letterhead.webp", "image/webp", 5 * 1024 * 1024))).isNull()
        assertThat(SetupLogoRules.validate(file("letterhead.png", "image/png", 5 * 1024 * 1024 + 1))).isEqualTo(LawFirmProblem.LOGO_TOO_LARGE)
        assertThat(SetupLogoRules.validate(file("letterhead.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 10)))
            .isEqualTo(LawFirmProblem.LOGO_TYPE)
    }

    @Test
    fun `r38 the law-firm profile needs a name, a well-formed admin email when given, and a non-negative employee count`() {
        val good = LawFirmProfile(name = "Mwangi & Otieno Advocates", adminEmail = "wanjiru@mwangiotieno.co.ke", employeeCount = 4)
        assertThat(LawFirmProfileRules.validate(good)).isNull()
        assertThat(LawFirmProfileRules.validate(good.copy(name = "  "))).isEqualTo(LawFirmProblem.NAME_REQUIRED)
        assertThat(LawFirmProfileRules.validate(good.copy(adminEmail = "wanjiru at mwangiotieno"))).isEqualTo(LawFirmProblem.INVALID_EMAIL)
        assertThat(LawFirmProfileRules.validate(good.copy(adminEmail = ""))).isNull()
        assertThat(LawFirmProfileRules.validate(good.copy(employeeCount = -1))).isEqualTo(LawFirmProblem.EMPLOYEE_COUNT_NEGATIVE)
        assertThat(LawFirmProfileRules.trimmed(good.copy(name = "  Mwangi & Otieno Advocates  ")).name).isEqualTo("Mwangi & Otieno Advocates")
    }

    @Test
    fun `r61 the setup stepper's step 2 also requires the admin name and email`() {
        val good = LawFirmProfile(name = "Mwangi & Otieno Advocates", adminName = "Wanjiru Mwangi", adminEmail = "wanjiru@mwangiotieno.co.ke")
        assertThat(LawFirmProfileRules.validateSetup(good)).isNull()
        assertThat(LawFirmProfileRules.validateSetup(good.copy(adminName = ""))).isEqualTo(LawFirmProblem.ADMIN_NAME_REQUIRED)
        assertThat(LawFirmProfileRules.validateSetup(good.copy(adminEmail = ""))).isEqualTo(LawFirmProblem.ADMIN_EMAIL_REQUIRED)
        assertThat(LawFirmProfileRules.validateSetup(good.copy(adminEmail = "nope"))).isEqualTo(LawFirmProblem.INVALID_EMAIL)
    }

    @Test
    fun `r39 the delete phrase matches case-insensitively and trimmed, nothing else`() {
        assertThat(DeleteAccountRules.matches("delete my account")).isTrue()
        assertThat(DeleteAccountRules.matches("  DELETE MY ACCOUNT ")).isTrue()
        assertThat(DeleteAccountRules.matches("delete my account now")).isFalse()
        assertThat(DeleteAccountRules.matches("")).isFalse()
    }

    @Test
    fun `r41 feedback needs a subject within 255 characters and text that survives the HTML strip`() {
        assertThat(FeedbackRules.validate("Cause list export", "<p>The Nakuru cause list PDF is blank&nbsp;today.</p>")).isNull()
        assertThat(FeedbackRules.validate("", "<p>text</p>")).isEqualTo(FeedbackProblem.SUBJECT_REQUIRED)
        assertThat(FeedbackRules.validate("x".repeat(256), "text")).isEqualTo(FeedbackProblem.SUBJECT_TOO_LONG)
        assertThat(FeedbackRules.validate("Subject", "<p><br></p>&nbsp;")).isEqualTo(FeedbackProblem.MESSAGE_REQUIRED)
        assertThat(FeedbackRules.plainText("<b>Milimani</b> &amp; <i>Kibera</i>  courts")).isEqualTo("Milimani & Kibera courts")
    }

    @Test
    fun `r35 the ai tour counter runs the full tour up to one login, nothing at two, new tools above two`() {
        assertThat(TourRules.decide(0)).isEqualTo(TourRules.Decision(TourKind.FULL, 2))
        assertThat(TourRules.decide(1)).isEqualTo(TourRules.Decision(TourKind.FULL, 2))
        assertThat(TourRules.decide(2)).isEqualTo(TourRules.Decision(TourKind.NONE, 2))
        assertThat(TourRules.decide(3)).isEqualTo(TourRules.Decision(TourKind.NEW_TOOLS, 4))
        assertThat(TourRules.decide(7)).isEqualTo(TourRules.Decision(TourKind.NEW_TOOLS, 8))
    }

    @Test
    fun `r35 the dms tour shows when show_tour is on or it was never completed`() {
        assertThat(TourRules.showDmsTour(OnboardingFlags(showTour = true, dmsTourCompleted = true))).isTrue()
        assertThat(TourRules.showDmsTour(OnboardingFlags(showTour = false, dmsTourCompleted = false))).isTrue()
        assertThat(TourRules.showDmsTour(OnboardingFlags(showTour = false, dmsTourCompleted = true))).isFalse()
    }

    @Test
    fun `r42 the product update shows only with should_show, content, a key, and no local seen flag`() {
        val update = ProductUpdate("lexken_ai_update_2026_10", shouldShow = true, updates = listOf(ProductUpdateItem("Faster research", "Streams sources.")))
        assertThat(ProductUpdateGate.shouldShow(update, seenLocally = false)).isTrue()
        assertThat(ProductUpdateGate.shouldShow(update, seenLocally = true)).isFalse()
        assertThat(ProductUpdateGate.shouldShow(update.copy(shouldShow = false), seenLocally = false)).isFalse()
        assertThat(ProductUpdateGate.shouldShow(update.copy(updates = emptyList()), seenLocally = false)).isFalse()
        assertThat(ProductUpdateGate.shouldShow(update.copy(updateKey = ""), seenLocally = false)).isFalse()
    }

    @Test
    fun `r42 smtp addresses split on commas and whitespace, lower-case, keep only emails, and dedupe`() {
        assertThat(SmtpRules.parseAddresses("Records@MwangiOtieno.co.ke, accounts@mwangiotieno.co.ke  not-an-email records@mwangiotieno.co.ke"))
            .containsExactly("records@mwangiotieno.co.ke", "accounts@mwangiotieno.co.ke")
        assertThat(SmtpRules.shouldPost(SmtpSettings())).isFalse()
        assertThat(SmtpRules.shouldPost(SmtpSettings(host = "smtp.mwangiotieno.co.ke"))).isTrue()
        assertThat(SmtpSettings().port).isEqualTo(587)
        assertThat(SmtpSettings().useTls).isTrue()
        assertThat(SmtpSettings().useSsl).isFalse()
    }

    @Test
    fun `r34 notification preferences default per switch and always patch all six keys`() {
        val prefs = NotificationPreferences.fromWire(mapOf("sms_alerts" to true, "unknown_key" to true))
        assertThat(prefs.isOn(NotificationPref.SMS_ALERTS)).isTrue()
        assertThat(prefs.isOn(NotificationPref.EMAIL_UPDATES)).isTrue()
        assertThat(NotificationPreferences.DEFAULTS.isOn(NotificationPref.SMS_ALERTS)).isFalse()
        assertThat(prefs.toggled(NotificationPref.PROMOTIONAL_EMAILS).toWire()).isEqualTo(
            mapOf(
                "email_updates" to true,
                "sms_alerts" to true,
                "in_app_new_features" to true,
                "promotional_emails" to false,
                "daily_case_reminders" to true,
                "monthly_cause_list" to true,
            ),
        )
    }
}
