package com.kosilka.feature.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class MapSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val sweepWidthMmFlow: Flow<Int> = dataStore.data.map { preferences ->
        val stored = preferences[SWEEP_WIDTH_MM_KEY]
        (stored ?: DEFAULT_SWEEP_WIDTH_MM).coerceIn(MIN_SWEEP_WIDTH_MM, MAX_SWEEP_WIDTH_MM)
    }

    suspend fun setSweepWidthMm(widthMm: Int) {
        val clamped = widthMm.coerceIn(MIN_SWEEP_WIDTH_MM, MAX_SWEEP_WIDTH_MM)
        dataStore.edit { preferences ->
            preferences[SWEEP_WIDTH_MM_KEY] = clamped
        }
    }

    private companion object {
        val SWEEP_WIDTH_MM_KEY = intPreferencesKey("map_sweep_width_mm")
        const val DEFAULT_SWEEP_WIDTH_MM = 350
        const val MIN_SWEEP_WIDTH_MM = 150
        const val MAX_SWEEP_WIDTH_MM = 1200
    }
}
