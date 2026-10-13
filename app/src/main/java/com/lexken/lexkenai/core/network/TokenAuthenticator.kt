package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.auth.SessionRenewer
import com.lexken.lexkenai.core.auth.TokenStore
import com.lexken.lexkenai.core.auth.session.SessionExpiryNotice
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * The single-refresh contract: a 401 triggers exactly one renewal, concurrent 401s
 * queue behind it, and the original request is replayed once with the new token.
 * A failed renewal clears the store — the session flow then roots the app to sign-in.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val renewer: SessionRenewer,
) : Authenticator {
    private val lock = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only ever replay once: a second 401 on the same call chain means the new token
        // was rejected too, and looping would hammer the backend.
        if (response.priorResponse != null) return null
        val failed = response.request.header("Authorization") ?: return null

        val newToken = runBlocking {
            lock.withLock {
                val current = tokenStore.current() ?: return@withLock null
                // Another queued call may have renewed while we waited for the lock.
                if ("Bearer ${current.accessToken}" != failed) return@withLock current.accessToken
                val renewed = renewer.renew(current)
                if (renewed == null) {
                    tokenStore.clear()
                    SessionExpiryNotice.arm() // the sign-in screen says why it is back
                    null
                } else {
                    tokenStore.save(renewed)
                    renewed.accessToken
                }
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
}
