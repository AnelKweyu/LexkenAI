package com.lexken.lexkenai.core.auth

/**
 * Obtains a fresh [AuthTokens] when the stored access token is rejected: a VF refresh grant
 * followed by the identity exchange. Returning null means the session is dead — the caller
 * clears the store and the app roots back to sign-in.
 */
fun interface SessionRenewer {
    suspend fun renew(current: AuthTokens): AuthTokens?
}

object NoOpSessionRenewer : SessionRenewer {
    override suspend fun renew(current: AuthTokens): AuthTokens? = null
}

/**
 * Tells the server a session is over, after the local wipe has already happened. Best-effort
 * by contract: the caller has already signed the user out and ignores the outcome.
 */
fun interface SessionRevoker {
    suspend fun revoke(tokens: AuthTokens)
}

object NoOpSessionRevoker : SessionRevoker {
    override suspend fun revoke(tokens: AuthTokens) = Unit
}
