package com.lexken.lexkenai.core.auth

import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result

class SignInUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): Result<SignInOutcome, AuthError> =
        repository.signIn(email.trim(), password)
}

class VerifyOtpUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(code: String): EmptyResult<AuthError> =
        repository.verifyOtp(code.trim())
}

class RequestPasswordResetUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String): EmptyResult<AuthError> =
        repository.requestPasswordReset(email.trim())
}

class ConfirmPasswordResetUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, code: String, newPassword: String): EmptyResult<AuthError> =
        repository.confirmPasswordReset(email.trim(), code.trim(), newPassword)
}

class RegisterUseCase(private val repository: AuthRepository) {
    /**
     * [phone] is whatever the user typed; the wire value is [PhoneRules.e164] (`+254…`). The email
     * is lower-cased like the Virtual Firm app does, so the code, the sign-in and the exchange all
     * name the same account.
     */
    suspend operator fun invoke(firstName: String, lastName: String, email: String, phone: String, password: String): EmptyResult<AuthError> =
        repository.register(firstName.trim(), lastName.trim(), email.trim().lowercase(), PhoneRules.e164(phone) ?: "", password)
}

class VerifyEmailOtpUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, code: String): Result<EmailVerificationOutcome, AuthError> =
        repository.verifyEmailOtp(email.trim().lowercase(), code.trim())
}

class ResendEmailOtpUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String): EmptyResult<AuthError> =
        repository.resendEmailOtp(email.trim().lowercase())
}

class RequestPhoneOtpUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(): Result<PhoneOtpDelivery, AuthError> = repository.requestPhoneOtp()
}

class VerifyPhoneOtpUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(code: String): EmptyResult<AuthError> =
        repository.verifyPhoneOtp(code.trim())
}

class CompletePendingSignInUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(): EmptyResult<AuthError> = repository.completePendingSignIn()
}

class RequestDistressHelpUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, message: String, errorDetails: String? = null): EmptyResult<AuthError> =
        repository.requestDistressHelp(email.trim(), message.trim(), errorDetails)
}

class VerifyResetTokenUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(token: String): EmptyResult<AuthError> =
        repository.verifyResetToken(token.trim())
}

class ConfirmResetWithTokenUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(token: String, newPassword: String): EmptyResult<AuthError> =
        repository.confirmResetWithToken(token.trim(), newPassword)
}
