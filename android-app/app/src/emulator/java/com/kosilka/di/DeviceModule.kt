package com.kosilka.di

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.emulator.DefaultEmulatorPath
import com.kosilka.core.emulator.EmulatorScenarioEngine
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.emulator.EmulatedMowerDevice
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Provides
    @Singleton
    fun provideEmulatorScenarioEngine(
        dispatchers: CoroutineDispatchers
    ): EmulatorScenarioEngine = EmulatorScenarioEngine(
        dispatchers = dispatchers,
        path = DefaultEmulatorPath.waypoints
    )

    @Provides
    @Singleton
    fun provideMowerDevice(
        emulatedMowerDevice: EmulatedMowerDevice
    ): MowerDevice = emulatedMowerDevice
}
