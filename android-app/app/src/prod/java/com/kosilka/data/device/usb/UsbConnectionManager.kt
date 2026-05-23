package com.kosilka.data.device.usb

import com.kosilka.data.device.ConnectionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbConnectionManager @Inject constructor() {
    private val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 8)

    val connectionEvents: Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()

    suspend fun connect(): Result<Unit> {
        connectionEventsFlow.emit(
            ConnectionEvent.Error(
                message = "USB transport not implemented yet for prod flavor"
            )
        )
        return Result.failure(UnsupportedOperationException("USB transport not implemented"))
    }

    suspend fun disconnect() {
        connectionEventsFlow.emit(ConnectionEvent.Disconnected)
    }
}
