package com.kosilka.data.device

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class TransportModeStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val modeFlow: Flow<TransportMode> = dataStore.data.map { preferences ->
        val rawMode = preferences[TRANSPORT_MODE_KEY]
        rawMode?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() } ?: TransportMode.USB
    }

    suspend fun currentMode(): TransportMode = modeFlow.first()

    suspend fun setMode(mode: TransportMode) {
        dataStore.edit { preferences ->
            preferences[TRANSPORT_MODE_KEY] = mode.name
        }
    }

    companion object {
        val TRANSPORT_MODE_KEY = stringPreferencesKey("transport_mode")
    }
}
