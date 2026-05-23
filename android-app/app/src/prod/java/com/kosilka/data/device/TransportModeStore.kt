package com.kosilka.data.device

import com.kosilka.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.remove
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

    val serviceEndpointFlow: Flow<String> = dataStore.data.map { preferences ->
        normalizeEndpoint(preferences[SERVICE_ENDPOINT_KEY])
            ?: normalizeEndpoint(BuildConfig.MOWER_SERVICE_BASE_URL)
            ?: DEFAULT_SERVICE_ENDPOINT
    }

    suspend fun currentServiceEndpoint(): String = serviceEndpointFlow.first()

    suspend fun setServiceEndpoint(endpoint: String) {
        val normalized = normalizeEndpoint(endpoint)
            ?: throw IllegalArgumentException("Service endpoint cannot be blank")
        dataStore.edit { preferences ->
            preferences[SERVICE_ENDPOINT_KEY] = normalized
        }
    }

    suspend fun resetServiceEndpoint() {
        dataStore.edit { preferences ->
            preferences.remove(SERVICE_ENDPOINT_KEY)
        }
    }

    private fun normalizeEndpoint(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        return trimmed.trimEnd('/')
    }

    companion object {
        val TRANSPORT_MODE_KEY = stringPreferencesKey("transport_mode")
        val SERVICE_ENDPOINT_KEY = stringPreferencesKey("service_endpoint")
        private const val DEFAULT_SERVICE_ENDPOINT = "http://10.0.2.2:8080"
    }
}
