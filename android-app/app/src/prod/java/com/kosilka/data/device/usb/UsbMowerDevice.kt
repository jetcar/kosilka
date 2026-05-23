package com.kosilka.data.device.usb

import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
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

    override suspend fun connect(): Result<Unit> = connectionManager.connect()

    override suspend fun disconnect() {
        connectionManager.disconnect()
    }

    override suspend fun send(envelope: Envelope): Result<Unit> = dataSource.send(envelope)
}
