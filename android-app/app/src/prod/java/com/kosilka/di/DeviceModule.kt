package com.kosilka.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kosilka.data.device.DelegatingMowerDevice
import com.kosilka.data.device.MowerDevice
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Provides
    @Singleton
    fun provideTransportModeDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("transport_mode.preferences_pb") }
    )

    @Provides
    @Singleton
    fun provideMowerDevice(
        delegatingMowerDevice: DelegatingMowerDevice
    ): MowerDevice = delegatingMowerDevice
}
