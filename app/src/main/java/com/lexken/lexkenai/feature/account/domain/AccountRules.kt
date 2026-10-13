package com.lexken.lexkenai.feature.account.domain

import com.lexken.lexkenai.core.model.FileInput

/** `UserProfile.jsx` `handleFileChange` (appendix auth #33): refused at pick time above 2 MB or outside JPG / PNG / GIF. */
object AvatarRules {
    const val MAX_BYTES = 2L * 1024 * 1024
    val TYPES = setOf("image/jpeg", "image/png", "image/gif")

    fun validate(file: FileInput): AvatarProblem? = when {
        file.sizeBytes > MAX_BYTES -> AvatarProblem.TOO_LARGE
        file.mimeType?.lowercase() !in TYPES -> AvatarProblem.TYPE
        else -> null
    }
}

/** Law-firm logo (appendix auth #36): 4 MB, JPG / PNG / GIF / SVG. */
object LogoRules {
    const val MAX_BYTES = 4L * 1024 * 1024
    val TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/svg+xml")

    fun validate(file: FileInput): LawFirmProblem? = when {
        file.sizeBytes > MAX_BYTES -> LawFirmProblem.LOGO_TOO_LARGE
        file.mimeType?.lowercase() !in TYPES -> LawFirmProblem.LOGO_TYPE
        else -> null
    }
}

/** The setup stepper's `login_logo` / `pdf_logo` (appendix auth #38, files rule 61): 5 MB, any `image/` type. */
object SetupLogoRules {
    const val MAX_BYTES = 5L * 1024 * 1024

    fun validate(file: FileInput): LawFirmProblem? = when {
        file.sizeBytes > MAX_BYTES -> LawFirmProblem.LOGO_TOO_LARGE
        file.mimeType?.lowercase()?.startsWith("image/") != true -> LawFirmProblem.LOGO_TYPE
        else -> null
    }
}

/**
 * The Law Firm tab's Yup schema (rule 38): name required, admin email must be an email when
 * given, employee count an integer ≥ 0. The stepper (rule 61, step 2) additionally requires the
 * admin name and email — [validateSetup].
 */
object LawFirmProfileRules {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isEmail(value: String): Boolean = EMAIL.matches(value.trim())

    fun trimmed(profile: LawFirmProfile): LawFirmProfile = profile.copy(
        name = profile.name.trim(),
        adminName = profile.adminName.trim(),
        adminEmail = profile.adminEmail.trim(),
        adminTel = profile.adminTel.trim(),
        address = profile.address.trim(),
    )

    fun validate(profile: LawFirmProfile): LawFirmProblem? = when {
        profile.name.isBlank() -> LawFirmProblem.NAME_REQUIRED
        profile.adminEmail.isNotBlank() && !isEmail(profile.adminEmail) -> LawFirmProblem.INVALID_EMAIL
        (profile.employeeCount ?: 0) < 0 -> LawFirmProblem.EMPLOYEE_COUNT_NEGATIVE
        else -> null
    }

    fun validateSetup(profile: LawFirmProfile): LawFirmProblem? = when {
        profile.name.isBlank() -> LawFirmProblem.NAME_REQUIRED
        profile.adminName.isBlank() -> LawFirmProblem.ADMIN_NAME_REQUIRED
        profile.adminEmail.isBlank() -> LawFirmProblem.ADMIN_EMAIL_REQUIRED
        !isEmail(profile.adminEmail) -> LawFirmProblem.INVALID_EMAIL
        else -> null
    }
}

/** `UserProfile.jsx:274-277` (rule 39): the typed phrase must equal `delete my account`, case-insensitively. */
object DeleteAccountRules {
    const val PHRASE = "delete my account"

    fun matches(typed: String): Boolean = typed.trim().equals(PHRASE, ignoreCase = true)
}

/**
 * `FeedBackModal.jsx` / `BugReportModal.jsx` (appendix auth #41): a subject (the serializer caps
 * it at 255) and a message that still has text once the editor's HTML is stripped.
 */
object FeedbackRules {
    const val SUBJECT_MAX_LENGTH = 255

    private val TAGS = Regex("<[^>]*>")
    private val ENTITIES = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'")

    /** The web's `stripHtml`: tags removed, entities decoded, whitespace collapsed. */
    fun plainText(html: String): String {
        var text = html.replace(TAGS, " ")
        ENTITIES.forEach { (entity, char) -> text = text.replace(entity, char) }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun validate(subject: String, message: String): FeedbackProblem? = when {
        subject.isBlank() -> FeedbackProblem.SUBJECT_REQUIRED
        subject.trim().length > SUBJECT_MAX_LENGTH -> FeedbackProblem.SUBJECT_TOO_LONG
        plainText(message).isEmpty() -> FeedbackProblem.MESSAGE_REQUIRED
        else -> null
    }
}

/**
 * `LawrisAI.jsx:317-341` (rule 35): `loginCount ≤ 1` runs the full tour and sets the counter to
 * 2; `> 2` runs the "new tools" tour and increments; exactly 2 runs nothing.
 */
object TourRules {
    data class Decision(val tour: TourKind, val nextLoginCount: Int)

    fun decide(loginCount: Int): Decision = when {
        loginCount <= 1 -> Decision(TourKind.FULL, nextLoginCount = 2)
        loginCount > 2 -> Decision(TourKind.NEW_TOOLS, nextLoginCount = loginCount + 1)
        else -> Decision(TourKind.NONE, nextLoginCount = loginCount)
    }

    /** `Dms.jsx:651-684`: the DMS tour runs when `show_tour` is on or it was never completed. */
    fun showDmsTour(flags: OnboardingFlags): Boolean = flags.showTour || !flags.dmsTourCompleted
}

/** `ProductUpdateModal.jsx:41-71` (rule 42): shown iff the server says so, it has content, and this key was not seen. */
object ProductUpdateGate {
    fun shouldShow(update: ProductUpdate, seenLocally: Boolean): Boolean =
        update.shouldShow && update.updates.isNotEmpty() && update.updateKey.isNotBlank() && !seenLocally
}

/** `EmailTags` (files rule 42): CC/BCC typed as text, split on commas and whitespace, lower-cased, valid emails only, deduped. */
object SmtpRules {
    fun parseAddresses(typed: String): List<String> =
        typed.split(Regex("[,\\s]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && LawFirmProfileRules.isEmail(it) }
            .distinct()

    /** Step 3 posts only when the user typed something (appendix auth #38). */
    fun shouldPost(smtp: SmtpSettings): Boolean = !smtp.isBlank
}
