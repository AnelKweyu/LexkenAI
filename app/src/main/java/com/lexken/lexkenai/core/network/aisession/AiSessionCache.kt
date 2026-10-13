package com.lexken.lexkenai.core.network.aisession

import com.lexken.lexkenai.core.common.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The web's module-scope `aiSessionToken` / `aiSessionExpiresAt` plus its single in-flight
 * mint promise: concurrent callers wait for one mint instead of each starting their own, and
 * a token is handed out only while [Clock] says it is live. In memory on purpose — the web
 * loses it on reload and re-mints, and so does the app on process death.
 *
 * A refused mint (the Turnstile gate, backend ask #1) is remembered for [refusalCooldownMillis]
 * so a burst of protected requests costs one refused mint, not one per request.
 */
class AiSessionCache(
    private val minter: AiSessionMinter,
    private val clock: Clock,
    private val refusalCooldownMillis: Long = DEFAULT_REFUSAL_COOLDOWN_MILLIS,
) {
    private val mutex = Mutex()

    @Volatile private var token: AiSessionToken? = null

    @Volatile private var refusedUntilMillis = 0L

    /** Mints on first use so a request never goes out without a session it could have had. */
    var mints = 0
        private set

    /** The live session for [deviceId], minting one when none is cached; null when the host refuses. */
    suspend fun current(deviceId: String): String? = mutex.withLock {
        live()?.let { return it.value }
        if (clock.nowMillis() < refusedUntilMillis) return null
        mintLocked(deviceId)?.value
    }

    /**
     * After a challenge on a request that carried [stale]: mint again — unless another caller
     * already replaced it while this one was waiting, in which case the replacement is reused.
     */
    suspend fun refresh(deviceId: String, stale: String?): String? = mutex.withLock {
        live()?.takeIf { it.value != stale }?.let { return it.value }
        token = null
        if (clock.nowMillis() < refusedUntilMillis) return null
        mintLocked(deviceId)?.value
    }

    fun invalidate() {
        token = null
    }

    private fun live(): AiSessionToken? = token?.takeIf { it.expiresAtMillis > clock.nowMillis() }

    private suspend fun mintLocked(deviceId: String): AiSessionToken? {
        mints += 1
        val minted = minter.mint(deviceId)
        token = minted
        if (minted == null) refusedUntilMillis = clock.nowMillis() + refusalCooldownMillis
        return minted
    }

    companion object {
        const val DEFAULT_REFUSAL_COOLDOWN_MILLIS = 60_000L
    }
}
