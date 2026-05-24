package com.kosilka.data.device.usb

import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.domain.model.Point2dMm
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbMowerDevice @Inject constructor(
    private val connectionManager: UsbConnectionManager,
    private val dataSource: UsbMowerDataSource
) : MowerDevice {

    override val connectionEvents: Flow<ConnectionEvent> = connectionManager.connectionEvents

    override val incomingMessages: Flow<IncomingMessage> = dataSource.incomingMessages

    override suspend fun connect(): Result<Unit> {
        val result = connectionManager.connect()
        if (result.isSuccess) {
            dataSource.startReading()
        }
        return result
    }

    override suspend fun disconnect() {
        dataSource.stopReading()
        connectionManager.disconnect()
    }

    override suspend fun send(envelope: Envelope): Result<Unit> = dataSource.send(envelope)

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        return Result.failure(
            UnsupportedOperationException("USB transport does not expose direct position feedback")
        )
    }
}
