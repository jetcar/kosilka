package com.kosilka.data.device.usb

import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbMowerDataSource @Inject constructor() {
    private val messagesFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)

    val incomingMessages: Flow<IncomingMessage> = messagesFlow.asSharedFlow()

    suspend fun send(envelope: Envelope): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException(
                "UsbMowerDataSource is not wired yet. messageType=${envelope.messageType}"
            )
        )
    }
}
