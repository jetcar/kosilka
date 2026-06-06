package com.kosilka.data.device

import android.util.Log
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.remote.RemoteServiceMowerDevice
import com.kosilka.data.device.usb.UsbMowerDevice
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.UwbTag
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

    override suspend fun connect(): Result<Unit> {
        val preferredMode = transportModeStore.currentMode()
        Log.i(TAG, "connect: preferredMode=$preferredMode")
        val preferredDevice = activeDeviceForMode(preferredMode)
        val preferredResult = preferredDevice.connect()
        if (preferredResult.isSuccess) {
            Log.i(TAG, "connect: preferred mode succeeded")
            return preferredResult
        }
        Log.w(TAG, "connect: preferred mode failed reason=${preferredResult.exceptionOrNull()?.message}")

        val fallbackMode = when (preferredMode) {
            TransportMode.USB -> TransportMode.SERVICE
            TransportMode.SERVICE -> TransportMode.USB
        }
        Log.i(TAG, "connect: trying fallbackMode=$fallbackMode")
        val fallbackDevice = activeDeviceForMode(fallbackMode)
        val fallbackResult = fallbackDevice.connect()
        if (fallbackResult.isSuccess) {
            transportModeStore.setMode(fallbackMode)
            Log.i(TAG, "connect: fallback mode succeeded; persisted mode=$fallbackMode")
            return fallbackResult
        }
        Log.e(TAG, "connect: fallback mode failed reason=${fallbackResult.exceptionOrNull()?.message}")

        return preferredResult
    }

    override suspend fun disconnect() {
        activeDevice().disconnect()
    }

    override suspend fun send(envelope: Envelope): Result<Unit> = activeDevice().send(envelope)

    override suspend fun readCurrentPosition(): Result<Point2dMm> = activeDevice().readCurrentPosition()

    override suspend fun getUwbTags(): Result<List<UwbTag>> = activeDevice().getUwbTags()

    override suspend fun toggleUwbTag(tagId: String): Result<Unit> = activeDevice().toggleUwbTag(tagId)

    private suspend fun activeDevice(): MowerDevice = activeDeviceForMode(transportModeStore.currentMode())

    private fun activeDeviceForMode(mode: TransportMode): MowerDevice {
        return when (mode) {
            TransportMode.USB -> usbMowerDevice
            TransportMode.SERVICE -> remoteServiceMowerDevice
        }
    }

    private companion object {
        const val TAG = "DelegatingMowerDevice"
    }
}
