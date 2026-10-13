package com.lexken.lexkenai.feature.account.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lexken.lexkenai.feature.account.domain.OnboardingFlags
import com.lexken.lexkenai.feature.account.domain.OnboardingStore
import com.lexken.lexkenai.feature.account.domain.ProductUpdateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "lexken_account")

/** The web's first-login localStorage keys, as one record under `lexken_account`. */
class DataStoreOnboardingStore(context: Context) : OnboardingStore {
    private val dataStore = context.applicationContext.accountDataStore

    override val flags: Flow<OnboardingFlags?> = dataStore.data.map { it.read() }

    override suspend fun current(): OnboardingFlags? = flags.first()

    override suspend fun update(transform: (OnboardingFlags) -> OnboardingFlags): OnboardingFlags {
        val prefs = dataStore.edit { prefs ->
            val next = transform(prefs.read() ?: OnboardingFlags())
            prefs[PRESENT] = true
            prefs[IS_FIRST_LOGIN] = next.isFirstLogin
            prefs[SHOW_TOUR] = next.showTour
            prefs[SHOW_LAWFIRM_SETUP] = next.showLawfirmSetup
            prefs[DMS_TOUR_COMPLETED] = next.dmsTourCompleted
            prefs[LOGIN_COUNT] = next.loginCount
            prefs[SETUP_SKIPPED] = next.lawfirmSetupSkipped
            prefs[SETUP_REMINDERS] = next.lawfirmSetupReminderCount
        }
        return prefs.read() ?: OnboardingFlags()
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> KEYS.forEach { prefs.remove(it) } }
    }

    private fun Preferences.read(): OnboardingFlags? {
        if (this[PRESENT] != true) return null
        return OnboardingFlags(
            isFirstLogin = this[IS_FIRST_LOGIN] ?: false,
            showTour = this[SHOW_TOUR] ?: false,
            showLawfirmSetup = this[SHOW_LAWFIRM_SETUP] ?: false,
            dmsTourCompleted = this[DMS_TOUR_COMPLETED] ?: false,
            loginCount = this[LOGIN_COUNT] ?: 0,
            lawfirmSetupSkipped = this[SETUP_SKIPPED] ?: false,
            lawfirmSetupReminderCount = this[SETUP_REMINDERS] ?: 0,
        )
    }

    private companion object {
        val PRESENT = booleanPreferencesKey("onboarding_present")
        val IS_FIRST_LOGIN = booleanPreferencesKey("is_first_login")
        val SHOW_TOUR = booleanPreferencesKey("show_tour")
        val SHOW_LAWFIRM_SETUP = booleanPreferencesKey("show_lawfirm_setup")
        val DMS_TOUR_COMPLETED = booleanPreferencesKey("dmsTourCompleted")
        val LOGIN_COUNT = intPreferencesKey("loginCount")
        val SETUP_SKIPPED = booleanPreferencesKey("lawfirm_setup_skipped")
        val SETUP_REMINDERS = intPreferencesKey("lawfirm_setup_reminder_count")
        val KEYS = listOf(PRESENT, IS_FIRST_LOGIN, SHOW_TOUR, SHOW_LAWFIRM_SETUP, DMS_TOUR_COMPLETED, LOGIN_COUNT, SETUP_SKIPPED, SETUP_REMINDERS)
    }
}

/** `seen_product_update_<key>` in the same DataStore. */
class DataStoreProductUpdateStore(context: Context) : ProductUpdateStore {
    private val dataStore = context.applicationContext.accountDataStore

    override suspend fun isSeen(updateKey: String): Boolean = dataStore.data.first()[key(updateKey)] == true

    override suspend fun markSeen(updateKey: String) {
        dataStore.edit { it[key(updateKey)] = true }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { prefs.remove(it) } }
    }

    private fun key(updateKey: String) = booleanPreferencesKey(PREFIX + updateKey)

    private companion object {
        const val PREFIX = "seen_product_update_"
    }
}

/** Test doubles — same contracts, no disk. */
class InMemoryOnboardingStore(initial: OnboardingFlags? = null) : OnboardingStore {
    private val state = MutableStateFlow(initial)
    override val flags: Flow<OnboardingFlags?> = state

    override suspend fun current(): OnboardingFlags? = state.value

    override suspend fun update(transform: (OnboardingFlags) -> OnboardingFlags): OnboardingFlags =
        transform(state.value ?: OnboardingFlags()).also { state.value = it }

    override suspend fun clear() {
        state.value = null
    }
}

class InMemoryProductUpdateStore : ProductUpdateStore {
    val seen = linkedSetOf<String>()

    override suspend fun isSeen(updateKey: String): Boolean = updateKey in seen

    override suspend fun markSeen(updateKey: String) {
        seen += updateKey
    }

    override suspend fun clear() = seen.clear()
}
