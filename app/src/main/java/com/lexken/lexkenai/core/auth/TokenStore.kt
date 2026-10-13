package com.lexken.lexkenai.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The stored session: [accessToken] is the exchanged Lawris JWT that every tenant-host call
 * carries; [refreshToken] is the identity host's (VF/EMS) OAuth refresh token, which the renewer
 * turns back into a fresh JWT.
 */
data class AuthTokens(val accessToken: String, val refreshToken: String?)

/** The only place tokens are persisted. Everything else asks this. */
interface TokenStore {
    val tokens: Flow<AuthTokens?>
    suspend fun current(): AuthTokens?
    suspend fun save(tokens: AuthTokens)
    suspend fun clear()
}

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "lexken_auth")

class DataStoreTokenStore(context: Context) : TokenStore {
    private val dataStore = context.applicationContext.authDataStore

    override val tokens: Flow<AuthTokens?> = dataStore.data.map { prefs ->
        prefs[ACCESS]?.let { AuthTokens(accessToken = it, refreshToken = prefs[REFRESH]) }
    }

    override suspend fun current(): AuthTokens? = tokens.first()

    override suspend fun save(tokens: AuthTokens) {
        dataStore.edit { prefs ->
            prefs[ACCESS] = tokens.accessToken
            tokens.refreshToken?.let { prefs[REFRESH] = it } ?: prefs.remove(REFRESH)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
    }
}

/** Test double — same contract, no disk. */
class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private val state = MutableStateFlow(initial)
    override val tokens: Flow<AuthTokens?> = state
    override suspend fun current(): AuthTokens? = state.value
    override suspend fun save(tokens: AuthTokens) {
        state.value = tokens
    }
    override suspend fun clear() {
        state.value = null
    }
}
