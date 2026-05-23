package com.kosilka.di

import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.usb.UsbMowerDevice
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
    fun provideMowerDevice(
        usbMowerDevice: UsbMowerDevice
    ): MowerDevice = usbMowerDevice
}
