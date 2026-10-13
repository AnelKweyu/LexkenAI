package com.lexken.lexkenai.feature.account.domain

import com.lexken.lexkenai.core.model.FileInput
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Error

// ── Profile & notifications (web `UserProfile.jsx`) ─────────────────────────

/**
 * The six switches the web renders, in its order. [wire] is the key inside the profile's
 * `notification_preferences` object; [scheduled] splits the two groups on screen.
 */
enum class NotificationPref(val wire: String, val scheduled: Boolean, val default: Boolean) {
    EMAIL_UPDATES("email_updates", scheduled = false, default = true),
    SMS_ALERTS("sms_alerts", scheduled = false, default = false),
    IN_APP_NEW_FEATURES("in_app_new_features", scheduled = false, default = true),
    PROMOTIONAL_EMAILS("promotional_emails", scheduled = true, default = true),
    DAILY_CASE_REMINDERS("daily_case_reminders", scheduled = true, default = true),
    MONTHLY_CAUSE_LIST("monthly_cause_list", scheduled = true, default = true),
    ;

    companion object {
        fun fromWire(key: String): NotificationPref? = entries.firstOrNull { it.wire == key }
    }
}

/** Every switch has a value: keys the server omits fall back to the web's defaults. */
data class NotificationPreferences(private val enabled: Map<NotificationPref, Boolean> = emptyMap()) {
    fun isOn(pref: NotificationPref): Boolean = enabled[pref] ?: pref.default

    fun with(pref: NotificationPref, on: Boolean): NotificationPreferences =
        NotificationPreferences(NotificationPref.entries.associateWith { if (it == pref) on else isOn(it) })

    fun toggled(pref: NotificationPref): NotificationPreferences = with(pref, !isOn(pref))

    /** The full object the web PATCHes — always all six keys. */
    fun toWire(): Map<String, Boolean> = NotificationPref.entries.associate { it.wire to isOn(it) }

    companion object {
        val DEFAULTS = NotificationPreferences()

        fun fromWire(map: Map<String, Boolean>): NotificationPreferences =
            NotificationPreferences(map.mapNotNull { (k, v) -> NotificationPref.fromWire(k)?.let { it to v } }.toMap())
    }
}

/** `GET users/profile/` (appendix auth #32): what the account pages read beyond the session's nav-info. */
data class AccountProfile(
    val fullName: String,
    val email: String,
    val phone: String? = null,
    val roleName: String? = null,
    val preferredLanguage: String? = null,
    val timezone: String? = null,
    val notifications: NotificationPreferences = NotificationPreferences.DEFAULTS,
    val avatarUrl: String? = null,
)

// ── Law firm (web Law Firm tab and the first-login setup stepper) ───────────

data class LawFirmProfile(
    val name: String = "",
    val adminName: String = "",
    val adminEmail: String = "",
    val adminTel: String = "",
    /** Server-owned; the web renders it disabled but its Yup schema still checks it. */
    val employeeCount: Int? = null,
    val address: String = "",
    val logoUrl: String? = null,
    /** Free-form server objects, carried through untouched (JSON text) on save. */
    val bankDetails: String? = null,
    val technicalDetails: String? = null,
)

/** The stepper's optional third step: `POST pms/smtp-config/` (appendix auth #38, files rule 42 defaults). */
data class SmtpSettings(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val useTls: Boolean = true,
    val useSsl: Boolean = false,
    val user: String = "",
    val password: String = "",
    val fromEmail: String = "",
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
) {
    /** The web only posts step 3 when the user typed something in it. */
    val isBlank: Boolean get() = host.isBlank() && user.isBlank() && password.isBlank() && fromEmail.isBlank()

    companion object {
        const val DEFAULT_PORT = 587
    }
}

/** What the first-login stepper collects (`LawFirmSetupStepper.jsx`). */
data class LawFirmSetup(
    val profile: LawFirmProfile,
    val loginLogo: FileInput? = null,
    val pdfLogo: FileInput? = null,
    val smtp: SmtpSettings = SmtpSettings(),
)

/** One completed stepper call, in order, so a journey can assert the exact sequence. */
enum class LawFirmSetupStep { LOGOS, PROFILE, SMTP }

sealed interface LawFirmError : Error {
    data class Invalid(val problem: LawFirmProblem) : LawFirmError

    /** The server's `detail` sentence when it has one. */
    data class Rejected(val message: String) : LawFirmError

    data class Transport(val error: DataError) : LawFirmError
}

enum class LawFirmProblem : Error {
    NAME_REQUIRED,
    ADMIN_NAME_REQUIRED,
    ADMIN_EMAIL_REQUIRED,
    INVALID_EMAIL,
    EMPLOYEE_COUNT_NEGATIVE,
    LOGO_TOO_LARGE,
    LOGO_TYPE,
}

enum class AvatarProblem : Error { TOO_LARGE, TYPE }

// ── Support, deletion, product updates, onboarding ─────────────────────────

/** Product feedback vs a bug report — each has its own route (appendix auth #41). */
enum class FeedbackKind { FEEDBACK, BUG }

enum class FeedbackProblem : Error { SUBJECT_REQUIRED, MESSAGE_REQUIRED, SUBJECT_TOO_LONG }

sealed interface SupportError : Error {
    data class Invalid(val problem: FeedbackProblem) : SupportError

    /** A 400 with the serializer's field sentences, first field first. */
    data class Rejected(val message: String) : SupportError

    data class Transport(val error: DataError) : SupportError
}

sealed interface DeleteAccountError : Error {
    data object PhraseMismatch : DeleteAccountError

    data class Rejected(val message: String) : DeleteAccountError

    data class Transport(val error: DataError) : DeleteAccountError
}

/** `GET ai/product-updates/latest/` (appendix auth #59). */
data class ProductUpdate(
    val updateKey: String,
    val shouldShow: Boolean,
    val title: String? = null,
    val subtitle: String? = null,
    val updates: List<ProductUpdateItem> = emptyList(),
) {
    companion object {
        /** The web's fail-open value when the request fails. */
        val NONE = ProductUpdate(updateKey = "", shouldShow = false)
    }
}

data class ProductUpdateItem(val title: String, val message: String, val icon: String? = null)

/**
 * The web's first-login localStorage flags (appendix storage keys, rules 17 and 35), as one
 * persisted record. The identity exchange does not return `is_first_login` / `show_tour` /
 * `show_lawfirm_setup`, so mobile derives the first sign-in from "no record persisted yet".
 */
data class OnboardingFlags(
    val isFirstLogin: Boolean = false,
    val showTour: Boolean = false,
    val showLawfirmSetup: Boolean = false,
    val dmsTourCompleted: Boolean = false,
    val loginCount: Int = 0,
    val lawfirmSetupSkipped: Boolean = false,
    val lawfirmSetupReminderCount: Int = 0,
)

/** What the `/ai` page does with `loginCount` when it mounts (rule 35). */
enum class TourKind { FULL, NEW_TOOLS, NONE }
