package com.lexken.lexkenai.core.auth

import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Error
import com.lexken.lexkenai.core.result.Result

sealed interface AuthError : Error {
    data object InvalidCredentials : AuthError
    /**
     * The identity host refused the sign-in and said why in a sentence of its own — e.g. the
     * account's 2FA channel is SMS but it has no phone number, or the host's rate limit. Shown
     * verbatim; nothing the app can retry on its own.
     */
    data class SignInBlocked(val message: String) : AuthError
    /**
     * The credentials were right but the account has two-factor on and the identity host's mobile
     * grant never issued the code: every call with the token answers 403 "Two Factor
     * Authentication not verified", which the Lawris exchange reports as a dead token. Until the
     * host runs its 2FA step on `oauth2/mobile-token/` (`middleware.py` only does so for
     * `oauth2/token/`), such an account signs in on the web or turns 2FA off there.
     */
    data object TwoFactorBlocked : AuthError
    data object EmailAlreadyRegistered : AuthError
    /**
     * The identity host accepted the credentials but the account's email is not verified, so the
     * Lawris exchange refuses it (`lexken_auth/services.py`, 403 "email is not verified"). The
     * credentials are remembered so verifying the emailed code finishes the sign-in.
     */
    data object EmailUnverified : AuthError
    /** The reset code was wrong or has expired — the user re-enters or requests a new one. */
    data object ResetCodeInvalid : AuthError
    /** The emailed reset link's token is invalid, used or older than its 30-minute TTL. */
    data object ResetLinkInvalid : AuthError
    /** The server's password validators rejected the new password (e.g. same as the old one). */
    data object NewPasswordRejected : AuthError
    /** The 2FA or verification code was rejected — the user re-enters it. */
    data object OtpInvalid : AuthError
    /** The verification code ran out — resend and try again. */
    data object OtpExpired : AuthError
    /** The account (or phone) is already verified — just sign in. */
    data object AlreadyVerified : AuthError
    /** A phone code was requested but the account has no phone number. */
    data object NoPhoneOnAccount : AuthError
    /**
     * No sign-in is awaiting a step (a 2FA code, or the phone step after email verification) —
     * e.g. after process death — so the user signs in again.
     */
    data object MfaSessionExpired : AuthError
    data class Data(val error: DataError) : AuthError
}

/** How a phone code reached the user, as reported by the identity host. */
data class PhoneOtpDelivery(val channel: OtpDelivery)

/** What happened after a successful email verification. */
enum class EmailVerificationOutcome {
    /**
     * Verified and the remembered credentials minted a session, held back so the phone can be
     * verified first — continue on the phone screen; [AuthRepository.completePendingSignIn]
     * (or skipping) finishes the sign-in.
     */
    PHONE_VERIFICATION_OFFERED,
    /** Verified, but the user must sign in themselves (no remembered credentials, or they failed). */
    SIGN_IN_REQUIRED,
}

/** How the 2FA code reaches the user, as reported by the OAuth server. */
enum class OtpDelivery { SMS, WHATSAPP, EMAIL, UNKNOWN }

sealed interface SignInOutcome {
    data object SignedIn : SignInOutcome
    /** Credentials were right but 2FA is on: a code is on its way via [delivery] — verify it next. */
    data class MfaRequired(val delivery: OtpDelivery) : SignInOutcome
}

/**
 * The Virtual Law Firm (EMS) identity rail — the only rail whose sign-up accepts the mobile
 * bypass headers. On success the session becomes `SessionState.SignedIn`; the app root reacts
 * to that, not to these results.
 */
interface AuthRepository {
    suspend fun signIn(email: String, password: String): Result<SignInOutcome, AuthError>

    /**
     * Verify the 2FA code for the sign-in that returned [SignInOutcome.MfaRequired]. A verified
     * code rotates the identity session: the host deletes the interim pair and answers with a new
     * one, which is what gets exchanged. The pending sign-in lives in memory only (tokens never
     * touch navigation or saved state), so after process death this fails with
     * [AuthError.MfaSessionExpired] and the user signs in again.
     */
    suspend fun verifyOtp(code: String): EmptyResult<AuthError>

    /**
     * Create the account on the identity host (`authentication/signup/`, an individual client).
     * On success the account is unverified: a 6-digit code is on its way by email — continue on
     * the verify-email screen. The credentials are remembered in memory so [verifyEmailOtp] can
     * finish with an automatic sign-in; process death forfeits that.
     */
    suspend fun register(firstName: String, lastName: String, email: String, phone: String, password: String): EmptyResult<AuthError>

    /**
     * `authentication/email/verify/` — verify the emailed 6-digit code. The endpoint returns no
     * tokens; when the registration (or unverified sign-in) credentials are still remembered the
     * rail mints a session and holds it for the phone step, otherwise the user signs in themselves.
     */
    suspend fun verifyEmailOtp(email: String, code: String): Result<EmailVerificationOutcome, AuthError>

    /** `authentication/email/resend_code/` — the UI keeps a 60 s cooldown; the host throttles too. */
    suspend fun resendEmailOtp(email: String): EmptyResult<AuthError>

    /**
     * `authentication/phone/verify/send/` for the sign-in held after email verification — the host
     * picks SMS or WhatsApp and says which. Fails with [AuthError.MfaSessionExpired] when nothing
     * is held (process death).
     */
    suspend fun requestPhoneOtp(): Result<PhoneOtpDelivery, AuthError>

    /** `authentication/phone/verify/confirm/` for the held sign-in. Does not finish the sign-in. */
    suspend fun verifyPhoneOtp(code: String): EmptyResult<AuthError>

    /**
     * Finish the sign-in held after email verification (with or without the phone step):
     * identity exchange → stored tokens → session. Fails with [AuthError.MfaSessionExpired] when
     * nothing is held.
     */
    suspend fun completePendingSignIn(): EmptyResult<AuthError>

    /** Ask for a reset code to be sent to [email]'s account. Succeeds without leaking whether the account exists. */
    suspend fun requestPasswordReset(email: String): EmptyResult<AuthError>

    /** Complete the reset with the emailed/texted [code] and the new password. Does not sign in. */
    suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): EmptyResult<AuthError>

    /** `auth/distress-help/` — files a help request after repeated sign-in failure; answers 202. */
    suspend fun requestDistressHelp(email: String, message: String, errorDetails: String?): EmptyResult<AuthError>

    /** `auth/password-reset/verify-token/` — is the emailed link's token still good (30 min TTL)? */
    suspend fun verifyResetToken(token: String): EmptyResult<AuthError>

    /** `auth/password-reset/confirm/` — set the new password for the link's token. Does not sign in. */
    suspend fun confirmResetWithToken(token: String, newPassword: String): EmptyResult<AuthError>
}
