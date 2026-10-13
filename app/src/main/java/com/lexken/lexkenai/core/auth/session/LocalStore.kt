package com.lexken.lexkenai.core.auth.session

/**
 * Any per-account local state that must not outlive the session: feature DataStores, in-memory
 * notices, caches. Sign-out and account deletion clear every binding (`getAll<LocalStore>()`),
 * which is what the web's `localStorage.clear()` does on deletion — and what its logout forgets
 * to do (appendix auth rule 36).
 */
fun interface LocalStore {
    suspend fun clear()
}
