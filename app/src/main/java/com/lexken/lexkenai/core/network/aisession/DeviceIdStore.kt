package com.lexken.lexkenai.core.network.aisession

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID

/**
 * The web's `localStorage.lawris_ai_device_id` (httpService.jsx:95-110): a UUID made once and
 * never rotated — the AI session is bound to it server-side (`validate_ai_session`), so a new
 * id would invalidate every session minted for the old one.
 */
interface DeviceIdStore {
    suspend fun getOrCreate(): String
}

private val Context.aiSessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "lexken_ai_session")

class DataStoreDeviceIdStore(context: Context) : DeviceIdStore {
    private val dataStore = context.applicationContext.aiSessionDataStore

    override suspend fun getOrCreate(): String {
        // One edit both reads and, if needed, writes, so two first callers cannot mint two ids.
        val prefs = dataStore.edit { prefs ->
            if (prefs[DEVICE_ID] == null) prefs[DEVICE_ID] = UUID.randomUUID().toString()
        }
        return prefs[DEVICE_ID] ?: error("device id missing after edit")
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("lawris_ai_device_id")
    }
}

/** Test double — same contract, no disk. */
class InMemoryDeviceIdStore(private val id: String = UUID.randomUUID().toString()) : DeviceIdStore {
    override suspend fun getOrCreate(): String = id
}
