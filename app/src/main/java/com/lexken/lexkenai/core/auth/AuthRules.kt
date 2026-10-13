package com.lexken.lexkenai.core.auth

enum class EmailValidation { VALID, BLANK, INVALID }

enum class PasswordStrength { TOO_SHORT, WEAK, FAIR, STRONG }

enum class NameValidation { VALID, BLANK, TOO_SHORT }

enum class CodeValidation { VALID, BLANK, MALFORMED }

enum class PhoneValidation { VALID, BLANK, INVALID }

object EmailRules {
    private val EMAIL = Regex("""^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""")

    fun validate(email: String): EmailValidation {
        val trimmed = email.trim()
        return when {
            trimmed.isEmpty() -> EmailValidation.BLANK
            !EMAIL.matches(trimmed) -> EmailValidation.INVALID
            else -> EmailValidation.VALID
        }
    }
}

/**
 * The web's public sign-up policy: at least eight characters with an uppercase letter, a
 * lowercase letter and a digit. Each rule is reported separately so the form can tick them off
 * as the user types.
 */
data class PasswordPolicy(
    val minLength: Boolean,
    val uppercase: Boolean,
    val lowercase: Boolean,
    val digit: Boolean,
) {
    val satisfied: Boolean get() = minLength && uppercase && lowercase && digit

    companion object {
        val NONE = PasswordPolicy(minLength = false, uppercase = false, lowercase = false, digit = false)
    }
}

object PasswordRules {
    const val MIN_LENGTH = 8

    fun policy(password: String): PasswordPolicy = PasswordPolicy(
        minLength = password.length >= MIN_LENGTH,
        uppercase = password.any { it.isUpperCase() },
        lowercase = password.any { it.isLowerCase() },
        digit = password.any { it.isDigit() },
    )

    /**
     * Strength: < 8 chars → TOO_SHORT; then count of character classes (lower, upper, digit, symbol):
     * 1 → WEAK, 2–3 → FAIR, 4 or length ≥ 14 with ≥ 3 classes → STRONG.
     */
    fun strength(password: String): PasswordStrength {
        if (password.length < MIN_LENGTH) return PasswordStrength.TOO_SHORT
        val classes = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        return when {
            classes >= 4 || (classes >= 3 && password.length >= 14) -> PasswordStrength.STRONG
            classes >= 2 -> PasswordStrength.FAIR
            else -> PasswordStrength.WEAK
        }
    }
}

/**
 * Kenyan mobile numbers as the web's public sign-up takes them: the user types the nine digits
 * after the country code (`^[17]\d{8}$`, e.g. `712345678`), leading zeros are dropped. A number
 * pasted with `+254`, `254` or `0` in front normalises to the same nine digits so the rule never
 * rejects a correct number for its prefix. The identity host's sign-up stores the number in
 * E.164 (`+254712345678`, as the Virtual Firm app sends it).
 */
object PhoneRules {
    const val COUNTRY_CODE = "254"
    private val LOCAL = Regex("""^[17]\d{8}$""")

    /** The nine local digits, or null when the input is not a Kenyan mobile number. */
    fun localDigits(raw: String): String? {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith(COUNTRY_CODE) && digits.length > 9) digits = digits.removePrefix(COUNTRY_CODE)
        digits = digits.trimStart('0')
        return digits.takeIf { LOCAL.matches(it) }
    }

    /** `254712345678` — the country code plus the nine local digits. */
    fun normalise(raw: String): String? = localDigits(raw)?.let { COUNTRY_CODE + it }

    /** `+254712345678` — what `authentication/signup/` stores in `phone_number`. */
    fun e164(raw: String): String? = normalise(raw)?.let { "+$it" }

    fun validate(raw: String): PhoneValidation = when {
        raw.isBlank() -> PhoneValidation.BLANK
        localDigits(raw) == null -> PhoneValidation.INVALID
        else -> PhoneValidation.VALID
    }
}

/** Reset and 2FA codes are 6 digits, per the OAuth server's OTP format. */
object OtpRules {
    const val LENGTH = 6

    fun validate(code: String): CodeValidation {
        val trimmed = code.trim()
        return when {
            trimmed.isEmpty() -> CodeValidation.BLANK
            trimmed.length != LENGTH || trimmed.any { !it.isDigit() } -> CodeValidation.MALFORMED
            else -> CodeValidation.VALID
        }
    }
}

object NameRules {
    fun validate(name: String): NameValidation {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> NameValidation.BLANK
            trimmed.length < 2 -> NameValidation.TOO_SHORT
            else -> NameValidation.VALID
        }
    }
}

/**
 * The reset email links to `https://<host>/reset-password/{uid}/{token}`. Only the token
 * matters — `uid` is cosmetic and no endpoint decodes it (`verify-token`/`confirm` take
 * `token` alone). Foreign hosts, other paths and malformed links parse to null.
 */
object ResetLink {
    private val HOSTS = setOf("lexken.ai", "www.lexken.ai", "stage.lexken.ai")
    private val TOKEN = Regex("""^[A-Za-z0-9_-]{10,}$""")

    fun parse(url: String): String? {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val schemeSplit = withoutQuery.split("://", limit = 2)
        if (schemeSplit.size != 2 || schemeSplit[0].lowercase() != "https") return null
        val segments = schemeSplit[1].split('/').filter { it.isNotEmpty() }
        if (segments.size != 4) return null
        val (host, path, _, token) = segments
        if (host.lowercase() !in HOSTS) return null
        if (path != "reset-password") return null
        return token.takeIf { TOKEN.matches(it) }
    }
}
