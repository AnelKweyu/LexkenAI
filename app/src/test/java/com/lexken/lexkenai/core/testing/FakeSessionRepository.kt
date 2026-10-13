package com.lexken.lexkenai.core.testing

import com.lexken.lexkenai.core.auth.session.SessionRepository
import com.lexken.lexkenai.core.auth.session.SessionState
import com.lexken.lexkenai.core.model.Credits
import com.lexken.lexkenai.core.model.User
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Hand-rolled fake: tests drive [stateFlow] and can script failures. */
class FakeSessionRepository(
    initial: SessionState = SessionState.SignedOut,
) : SessionRepository {
    val stateFlow = MutableStateFlow(initial)
    override val state: Flow<SessionState> = stateFlow

    var refreshError: DataError? = null
    var signOutError: DataError? = null
    var signOutCalls = 0
        private set
    var refreshCalls = 0
        private set

    override suspend fun refresh(): Result<SessionState, DataError> {
        refreshCalls++
        return refreshError?.let { Result.Failure(it) } ?: Result.Success(stateFlow.value)
    }

    override suspend fun signOut(): EmptyResult<DataError> {
        signOutCalls++
        signOutError?.let { return Result.Failure(it) }
        stateFlow.value = SessionState.SignedOut
        return Result.Success(Unit)
    }

    fun signIn(user: User, credits: Credits) {
        stateFlow.value = SessionState.SignedIn(user, credits)
    }
}
