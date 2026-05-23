package com.kosilka.data.device

import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.remote.RemoteServiceMowerDevice
import com.kosilka.data.device.usb.UsbMowerDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DelegatingMowerDevice @Inject constructor(
    private val usbMowerDevice: UsbMowerDevice,
    private val remoteServiceMowerDevice: RemoteServiceMowerDevice,
    private val transportModeStore: TransportModeStore
) : MowerDevice {

    override val connectionEvents: Flow<ConnectionEvent> = transportModeStore.modeFlow
        .distinctUntilChanged()
        .flatMapLatest { mode -> activeDeviceForMode(mode).connectionEvents }

    override val incomingMessages: Flow<IncomingMessage> = transportModeStore.modeFlow
        .distinctUntilChanged()
        .flatMapLatest { mode -> activeDeviceForMode(mode).incomingMessages }

    override suspend fun connect(): Result<Unit> = activeDevice().connect()

    override suspend fun disconnect() {
        activeDevice().disconnect()
    }

    override suspend fun send(envelope: Envelope): Result<Unit> = activeDevice().send(envelope)

    private suspend fun activeDevice(): MowerDevice = activeDeviceForMode(transportModeStore.currentMode())

    private fun activeDeviceForMode(mode: TransportMode): MowerDevice {
        return when (mode) {
            TransportMode.USB -> usbMowerDevice
            TransportMode.SERVICE -> remoteServiceMowerDevice
        }
    }
}
