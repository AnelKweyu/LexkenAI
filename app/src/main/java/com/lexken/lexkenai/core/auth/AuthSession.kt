package com.lexken.lexkenai.core.auth

import com.lexken.lexkenai.core.auth.session.PasswordChangeFailure
import com.lexken.lexkenai.core.auth.session.PasswordChanger
import com.lexken.lexkenai.core.auth.session.SessionRepository
import com.lexken.lexkenai.core.auth.session.SessionState
import com.lexken.lexkenai.core.network.safeCall
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import com.lexken.lexkenai.core.result.map
import com.lexken.lexkenai.core.result.mapError
import com.lexken.lexkenai.core.result.onSuccess
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

internal fun String?.toOtpDelivery(): OtpDelivery = when (this?.lowercase()) {
    "sms" -> OtpDelivery.SMS
    "whatsapp" -> OtpDelivery.WHATSAPP
    "email" -> OtpDelivery.EMAIL
    else -> OtpDelivery.UNKNOWN
}

/**
 * The two-host auth bridge, owned in one place. Sign-up, the email and phone codes and the
 * password reset happen on the identity host (EMS / Virtual Law Firm); sign-in is a VF
 * password grant → identity exchange → Lawris JWT. The exchange creates the Lawris account for
 * a new EMS identity itself (unusable password, email verified, free subscription) and refuses
 * an identity whose email is not verified — which is why every path below verifies before it
 * exchanges.
 *
 * The stored access token is the exchanged JWT; the stored refresh token is the VF OAuth refresh
 * token, which [AuthSessionRenewer] turns back into a fresh JWT.
 */
class AuthSession(
    private val vfApi: VfOAuthApi,
    private val exchangeApi: ExchangeApi,
    private val accountApi: VfAccountApi,
    private val lawrisApi: LawrisAuthApi,
    private val config: VfConfig,
    private val tokenStore: TokenStore,
    private val sessionRepository: SessionRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AuthRepository {

    /**
     * The mint that answered `two_factor_enabled` — held in memory only so the interim token
     * never reaches navigation, saved state or disk. Process death forfeits it by design.
     */
    private var pendingMfa: VfTokenResponse? = null

    /**
     * Credentials whose account still needs its email verified — a fresh registration, or a
     * sign-in the exchange refused as unverified. Held in memory only so [verifyEmailOtp] can
     * finish with an automatic sign-in; process death forfeits it and verification then reports
     * SIGN_IN_REQUIRED.
     */
    private var pendingCredentials: PendingCredentials? = null

    private data class PendingCredentials(val email: String, val password: String)

    /**
     * The VF mint made after email verification, held back for the phone step. Its access token
     * is what `phone/verify/send` and `confirm` need; [completePendingSignIn] exchanges it. Memory only.
     */
    private var heldMint: VfTokenResponse? = null

    override suspend fun signIn(email: String, password: String): Result<SignInOutcome, AuthError> {
        pendingMfa = null
        val vfTokens = when (val minted = mint(email, password)) {
            is Result.Failure -> return Result.Failure(minted.error.network.toSignInError())
            is Result.Success -> minted.data
        }
        if (vfTokens.twoFactorEnabled == true) {
            pendingMfa = vfTokens
            return Result.Success(SignInOutcome.MfaRequired(vfTokens.otpDeliveryMethod.toOtpDelivery()))
        }
        return when (val completed = exchangeAndComplete(vfTokens)) {
            is Result.Success -> Result.Success(SignInOutcome.SignedIn)
            is Result.Failure -> {
                // The exchange knows the email is unverified; remember the credentials so the
                // code screen can finish the sign-in the way a registration does.
                if (completed.error == AuthError.EmailUnverified) pendingCredentials = PendingCredentials(email, password)
                Result.Failure(completed.error)
            }
        }
    }

    override suspend fun verifyOtp(code: String): EmptyResult<AuthError> {
        val pending = pendingMfa ?: return Result.Failure(AuthError.MfaSessionExpired)
        val verified = safeCall {
            accountApi.verifyLoginOtp("Bearer ${pending.accessToken}", OtpVerifyRequest(code))
        }
        if (verified is Result.Failure) return Result.Failure(verified.error.toOtpError())
        return exchangeAndComplete(pending).onSuccess { pendingMfa = null }
    }

    override suspend fun requestPasswordReset(email: String): EmptyResult<AuthError> =
        safeCall {
            accountApi.requestPasswordReset(
                bypassKey = config.bypassKey,
                nonce = nonce(),
                bundleId = config.bundleId,
                body = PasswordResetRequest(email),
            )
        }.map { }.mapError { AuthError.Data(it) }

    override suspend fun confirmPasswordReset(
        email: String,
        code: String,
        newPassword: String,
    ): EmptyResult<AuthError> =
        safeCall {
            accountApi.confirmPasswordReset(
                bypassKey = config.bypassKey,
                nonce = nonce(),
                bundleId = config.bundleId,
                body = PasswordResetConfirmRequest(email = email, code = code, password = newPassword),
            )
        }.map { }.mapError { it.toResetError() }

    // ── registration + the email and phone codes (identity host) ────────────

    override suspend fun register(
        firstName: String,
        lastName: String,
        email: String,
        phone: String,
        password: String,
    ): EmptyResult<AuthError> {
        pendingCredentials = null
        heldMint = null
        val request = VfSignupRequest(
            email = email,
            firstName = firstName,
            lastName = lastName,
            password = password,
            phoneNumber = phone,
            accountType = INDIVIDUAL_ACCOUNT,
        )
        return when (val result = httpCall(json) { accountApi.signup(config.bypassKey, nonce(), config.bundleId, request) }) {
            is Result.Success -> {
                pendingCredentials = PendingCredentials(email, password)
                Result.Success(Unit)
            }
            is Result.Failure -> Result.Failure(
                when {
                    // DRF's unique validator: {"email": ["user with this email already exists."]}
                    result.error.body?.field("email")?.contains("exist", ignoreCase = true) == true -> AuthError.EmailAlreadyRegistered
                    result.error.network == DataError.Network.CONFLICT -> AuthError.EmailAlreadyRegistered
                    else -> AuthError.Data(result.error.network)
                },
            )
        }
    }

    override suspend fun verifyEmailOtp(email: String, code: String): Result<EmailVerificationOutcome, AuthError> {
        val verified = httpCall(json) {
            accountApi.verifyEmail(config.bypassKey, nonce(), config.bundleId, EmailVerifyRequest(email = email, code = code))
        }
        if (verified is Result.Failure) {
            return Result.Failure(
                when {
                    verified.error.mentions("expired") -> AuthError.OtpExpired
                    verified.error.mentions("already verified") -> AuthError.AlreadyVerified
                    verified.error.network == DataError.Network.UNKNOWN -> AuthError.OtpInvalid
                    else -> AuthError.Data(verified.error.network)
                },
            )
        }
        val pending = pendingCredentials
        if (pending == null || !pending.email.equals(email, ignoreCase = true)) {
            return Result.Success(EmailVerificationOutcome.SIGN_IN_REQUIRED)
        }
        // Verification itself succeeded — never fail the screen because the mint didn't.
        val minted = mint(pending.email, pending.password).let { (it as? Result.Success)?.data }
            ?: return Result.Success(EmailVerificationOutcome.SIGN_IN_REQUIRED)
        if (minted.twoFactorEnabled == true) {
            // A 2FA account cannot be finished silently (the mobile grant never issues the
            // login code) — hand the user the ordinary sign-in.
            return Result.Success(EmailVerificationOutcome.SIGN_IN_REQUIRED)
        }
        heldMint = minted
        return Result.Success(EmailVerificationOutcome.PHONE_VERIFICATION_OFFERED)
    }

    override suspend fun resendEmailOtp(email: String): EmptyResult<AuthError> {
        val result = httpCall(json) {
            accountApi.resendEmailCode(config.bypassKey, nonce(), config.bundleId, EmailResendRequest(email))
        }
        return when (result) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(
                when {
                    result.error.mentions("already verified") -> AuthError.AlreadyVerified
                    else -> AuthError.Data(result.error.network)
                },
            )
        }
    }

    override suspend fun requestPhoneOtp(): Result<PhoneOtpDelivery, AuthError> {
        val held = heldMint ?: return Result.Failure(AuthError.MfaSessionExpired)
        val result = httpCall(json) {
            accountApi.sendPhoneCode(config.bypassKey, nonce(), config.bundleId, "Bearer ${held.accessToken}")
        }
        return when (result) {
            is Result.Success -> Result.Success(PhoneOtpDelivery(result.data.channel.toOtpDelivery()))
            is Result.Failure -> Result.Failure(
                when {
                    result.error.network == DataError.Network.UNAUTHORIZED -> AuthError.MfaSessionExpired.also { heldMint = null }
                    result.error.mentions("already verified") -> AuthError.AlreadyVerified
                    result.error.mentions("phone") && result.error.network == DataError.Network.UNKNOWN -> AuthError.NoPhoneOnAccount
                    else -> AuthError.Data(result.error.network)
                },
            )
        }
    }

    override suspend fun verifyPhoneOtp(code: String): EmptyResult<AuthError> {
        val held = heldMint ?: return Result.Failure(AuthError.MfaSessionExpired)
        val result = httpCall(json) {
            accountApi.confirmPhoneCode(
                config.bypassKey, nonce(), config.bundleId, "Bearer ${held.accessToken}", PhoneCodeConfirmRequest(code),
            )
        }
        return when (result) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(
                when {
                    result.error.network == DataError.Network.UNAUTHORIZED -> AuthError.MfaSessionExpired.also { heldMint = null }
                    result.error.mentions("expired") -> AuthError.OtpExpired
                    result.error.mentions("already verified") -> AuthError.AlreadyVerified
                    result.error.network == DataError.Network.UNKNOWN -> AuthError.OtpInvalid
                    else -> AuthError.Data(result.error.network)
                },
            )
        }
    }

    override suspend fun completePendingSignIn(): EmptyResult<AuthError> {
        val held = heldMint ?: return Result.Failure(AuthError.MfaSessionExpired)
        return exchangeAndComplete(held).onSuccess {
            heldMint = null
            pendingCredentials = null
        }
    }

    // ── the exchange ──────────────────────────────────────────────────────────

    private suspend fun mint(email: String, password: String): Result<VfTokenResponse, HttpFailure> = httpCall(json) {
        vfApi.token(
            bypassKey = config.bypassKey,
            nonce = nonce(),
            bundleId = config.bundleId,
            body = VfTokenRequest(
                grantType = "password",
                username = email,
                password = password,
                clientId = config.clientId,
                clientSecret = config.clientSecret,
            ),
        )
    }

    private suspend fun exchangeAndComplete(vfTokens: VfTokenResponse): EmptyResult<AuthError> =
        when (val exchanged = httpCall(json) { exchangeApi.exchange("Bearer ${vfTokens.accessToken}") }) {
            is Result.Failure -> Result.Failure(
                // `lexken_auth/services.py`: 403 "The Virtual Law Firm email is not verified."
                if (exchanged.error.network == DataError.Network.FORBIDDEN && exchanged.error.mentions("not verified")) {
                    AuthError.EmailUnverified
                } else {
                    AuthError.Data(exchanged.error.network)
                },
            )
            is Result.Success -> complete(
                AuthTokens(accessToken = exchanged.data.aiServiceToken, refreshToken = vfTokens.refreshToken),
            )
        }

    /** The OTP endpoint answers a wrong code with 400; an expired interim token with 401. */
    private fun DataError.Network.toOtpError(): AuthError = when (this) {
        DataError.Network.UNKNOWN -> AuthError.OtpInvalid
        DataError.Network.UNAUTHORIZED -> AuthError.MfaSessionExpired.also { pendingMfa = null }
        else -> AuthError.Data(this)
    }

    /** A wrong or expired reset code answers 400. */
    private fun DataError.Network.toResetError(): AuthError = when (this) {
        DataError.Network.UNKNOWN -> AuthError.ResetCodeInvalid
        else -> AuthError.Data(this)
    }

    // ── the Lawris-host leftovers: distress help and the emailed reset link ──

    override suspend fun requestDistressHelp(
        email: String,
        message: String,
        errorDetails: String?,
    ): EmptyResult<AuthError> =
        safeCall {
            lawrisApi.distressHelp(
                DistressHelpRequest(email = email, message = message, context = DISTRESS_CONTEXT, errorDetails = errorDetails),
            )
        }.map { }.mapError { AuthError.Data(it) }

    override suspend fun verifyResetToken(token: String): EmptyResult<AuthError> =
        safeCall { lawrisApi.verifyResetToken(ResetTokenRequest(token)) }
            .map { }
            .mapError { error ->
                if (error == DataError.Network.UNKNOWN) AuthError.ResetLinkInvalid else AuthError.Data(error)
            }

    override suspend fun confirmResetWithToken(token: String, newPassword: String): EmptyResult<AuthError> {
        val result = httpCall(json) {
            lawrisApi.confirmPasswordReset(
                ResetConfirmRequest(token = token, newPassword = newPassword, confirmPassword = newPassword),
            )
        }
        return when (result) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(
                when {
                    result.error.body?.fieldErrors?.containsKey("token") == true -> AuthError.ResetLinkInvalid
                    result.error.body?.fieldErrors?.keys?.any { it.endsWith("password") } == true -> AuthError.NewPasswordRejected
                    result.error.network == DataError.Network.UNKNOWN -> AuthError.ResetLinkInvalid
                    else -> AuthError.Data(result.error.network)
                },
            )
        }
    }

    private companion object {
        const val DISTRESS_CONTEXT = "android_sign_in"
        /** Consumer sign-ups are individual clients on the identity host, as the Virtual Firm app registers them. */
        const val INDIVIDUAL_ACCOUNT = "individual"
    }

    /** Persist tokens, then load the user; if that fails roll the tokens back so we never half-sign-in. */
    private suspend fun complete(tokens: AuthTokens): EmptyResult<AuthError> {
        tokenStore.save(tokens)
        return when (val refreshed = sessionRepository.refresh()) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> {
                tokenStore.clear()
                Result.Failure(AuthError.Data(refreshed.error))
            }
        }
    }

    /** The OAuth server answers a wrong password with 400/401 — both mean the credentials. */
    private fun DataError.Network.toSignInError(): AuthError = when (this) {
        DataError.Network.UNAUTHORIZED -> AuthError.InvalidCredentials
        DataError.Network.UNKNOWN -> AuthError.InvalidCredentials
        else -> AuthError.Data(this)
    }
}

/**
 * A fresh VF access token from the stored refresh grant. The stored access token is the Lawris
 * JWT, so every identity-host call that needs a bearer starts here (best-effort: null on refusal).
 */
internal suspend fun VfOAuthApi.accessFromRefresh(config: VfConfig, refreshToken: String): VfTokenResponse? = runCatching {
    token(
        bypassKey = config.bypassKey,
        nonce = nonce(),
        bundleId = config.bundleId,
        body = VfTokenRequest(
            grantType = "refresh_token",
            refreshToken = refreshToken,
            clientId = config.clientId,
            clientSecret = config.clientSecret,
        ),
    )
}.getOrNull()

/**
 * Renewal for `TokenAuthenticator`: the VF refresh grant, then the exchange. Null on failure —
 * the authenticator then clears the store and the app roots to sign-in.
 */
class AuthSessionRenewer(
    private val vfApi: VfOAuthApi,
    private val exchangeApi: ExchangeApi,
    private val config: VfConfig,
) : SessionRenewer {

    override suspend fun renew(current: AuthTokens): AuthTokens? {
        val refreshToken = current.refreshToken ?: return null
        val minted = vfApi.accessFromRefresh(config, refreshToken) ?: return null
        val exchanged = runCatching { exchangeApi.exchange("Bearer ${minted.accessToken}") }.getOrNull() ?: return null
        return AuthTokens(accessToken = exchanged.aiServiceToken, refreshToken = minted.refreshToken ?: refreshToken)
    }
}

/**
 * Server-side sign-out, after the local wipe: the stored VF refresh token is turned into a fresh
 * VF access and that session is revoked at `authentication/logout/`. Every failure is swallowed —
 * the user is already signed out.
 */
class AuthSessionRevoker(
    private val vfApi: VfOAuthApi,
    private val accountApi: VfAccountApi,
    private val config: VfConfig,
) : SessionRevoker {

    override suspend fun revoke(tokens: AuthTokens) {
        val refresh = tokens.refreshToken ?: return
        val minted = vfApi.accessFromRefresh(config, refresh) ?: return
        runCatching { accountApi.logout(config.bypassKey, nonce(), config.bundleId, "Bearer ${minted.accessToken}") }
    }
}

/**
 * Change the signed-in user's password on the identity host: the account the exchange created
 * on Lawris carries an unusable password, so Lawris `auth/password/change/` would change a
 * credential the app never signs in with. The host invalidates every token afterwards, so a
 * success re-mints with the new password and re-exchanges to keep the session alive; when that
 * re-mint fails the Lawris JWT still carries the session and the next renewal ends it with the
 * usual notice.
 */
class IdentityPasswordChanger(
    private val vfApi: VfOAuthApi,
    private val accountApi: VfAccountApi,
    private val exchangeApi: ExchangeApi,
    private val config: VfConfig,
    private val tokenStore: TokenStore,
    private val sessionRepository: SessionRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PasswordChanger {

    override suspend fun change(current: String, new: String): EmptyResult<PasswordChangeFailure> {
        val stored = tokenStore.current()
        val refresh = stored?.refreshToken
        if (stored == null || refresh == null) {
            return Result.Failure(PasswordChangeFailure.Transport(DataError.Network.UNAUTHORIZED))
        }
        val email = (sessionRepository.state.first() as? SessionState.SignedIn)?.user?.email
            ?: return Result.Failure(PasswordChangeFailure.Transport(DataError.Network.UNAUTHORIZED))
        val minted = vfApi.accessFromRefresh(config, refresh)
            ?: return Result.Failure(PasswordChangeFailure.Transport(DataError.Network.UNAUTHORIZED))

        val changed = httpCall(json) {
            accountApi.changePassword(
                config.bypassKey, nonce(), config.bundleId, "Bearer ${minted.accessToken}",
                VfPasswordChangeRequest(currentPassword = current, password = new),
            )
        }
        if (changed is Result.Failure) {
            val body = changed.error.body
            return Result.Failure(
                when {
                    changed.error.network != DataError.Network.UNKNOWN -> PasswordChangeFailure.Transport(changed.error.network)
                    body?.field("current") != null || changed.error.mentions("current password") -> PasswordChangeFailure.WrongCurrent
                    body?.firstSentence != null -> PasswordChangeFailure.Rejected(body.firstSentence!!)
                    else -> PasswordChangeFailure.Transport(changed.error.network)
                },
            )
        }

        // Keep the session: the old refresh token is dead now.
        runCatching {
            val reminted = vfApi.token(
                bypassKey = config.bypassKey,
                nonce = nonce(),
                bundleId = config.bundleId,
                body = VfTokenRequest(
                    grantType = "password",
                    username = email,
                    password = new,
                    clientId = config.clientId,
                    clientSecret = config.clientSecret,
                ),
            )
            val exchanged = exchangeApi.exchange("Bearer ${reminted.accessToken}")
            tokenStore.save(AuthTokens(accessToken = exchanged.aiServiceToken, refreshToken = reminted.refreshToken))
        }
        return Result.Success(Unit)
    }
}
