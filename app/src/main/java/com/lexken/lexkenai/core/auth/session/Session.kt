package com.lexken.lexkenai.core.auth.session

import com.lexken.lexkenai.core.model.Credits
import com.lexken.lexkenai.core.model.User
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Error
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.flow.Flow

/** Who is signed in right now. Observed by the app root to choose the auth vs main graph. */
sealed interface SessionState {
    data object Unknown : SessionState
    data object SignedOut : SessionState
    /** [subscriptionExpired] mirrors `sub/current/` `is_expired` — the web shows its expired-subscription modal on it. */
    data class SignedIn(val user: User, val credits: Credits, val subscriptionExpired: Boolean = false) : SessionState
}

interface SessionRepository {
    val state: Flow<SessionState>

    /** Re-fetches the current user and credits from the source of truth. */
    suspend fun refresh(): Result<SessionState, DataError>

    suspend fun signOut(): EmptyResult<DataError>
}

/**
 * Armed by the data layer when a session ends without the user asking for it — a token renewal
 * that failed, or a rejected token after the one renewal — and consumed once by the sign-in
 * screen, which then shows a one-line notice instead of the web's blocking dialog. A deliberate
 * sign-out never arms it.
 */
object SessionExpiryNotice {
    @Volatile private var armed = false

    fun arm() { armed = true }

    /** True once after [arm]; false thereafter. */
    fun consume(): Boolean {
        val was = armed
        armed = false
        return was
    }

    fun reset() { armed = false }
}

/**
 * Where the signed-in user's password lives. The app signs in through the Virtual Law Firm
 * identity host, and the Lawris account the exchange creates for it carries an unusable
 * password — so a password change must go to the identity host, not to Lawris.
 */
fun interface PasswordChanger {
    /** Change the password of the account behind the stored session and keep that session alive. */
    suspend fun change(current: String, new: String): EmptyResult<PasswordChangeFailure>
}

sealed interface PasswordChangeFailure : Error {
    /** The identity host rejected the current password. */
    data object WrongCurrent : PasswordChangeFailure
    /** The identity host rejected the new password; [message] is its own sentence. */
    data class Rejected(val message: String) : PasswordChangeFailure
    data class Transport(val error: DataError) : PasswordChangeFailure
}

class ObserveSessionUseCase(private val repository: SessionRepository) {
    operator fun invoke(): Flow<SessionState> = repository.state
}

/**
 * Re-fetches the signed-in user and credit balance. Call after anything that spends
 * credits server-side so every badge observing the session catches up.
 */
class RefreshSessionUseCase(private val repository: SessionRepository) {
    suspend operator fun invoke(): Result<SessionState, DataError> = repository.refresh()
}

class SignOutUseCase(private val repository: SessionRepository) {
    suspend operator fun invoke() = repository.signOut()
}

class ChangePasswordUseCase(private val changer: PasswordChanger) {
    suspend operator fun invoke(current: String, new: String): EmptyResult<PasswordChangeFailure> = changer.change(current, new)
}
