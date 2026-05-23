package com.kosilka.data.device.usb

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolDecoder
import com.kosilka.data.device.protocol.ProtocolEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class UsbMowerDataSource @Inject constructor(
    private val connectionManager: UsbConnectionManager,
    private val encoder: ProtocolEncoder,
    private val decoder: ProtocolDecoder,
    private val dispatchers: CoroutineDispatchers
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val messagesFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    private var readJob: Job? = null

    val incomingMessages: Flow<IncomingMessage> = messagesFlow.asSharedFlow()

    suspend fun send(envelope: Envelope): Result<Unit> {
        val session = connectionManager.currentSession()
            ?: return Result.failure(IllegalStateException("USB session is not open"))

        val payloadBytes = encoder.encode(envelope).toByteArray(Charsets.UTF_8)
        val lengthPrefix = ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payloadBytes.size)
            .array()

        val timeoutMs = 2_000
        val prefixWrite = session.connection.bulkTransfer(
            session.outEndpoint,
            lengthPrefix,
            lengthPrefix.size,
            timeoutMs
        )
        if (prefixWrite != lengthPrefix.size) {
            return Result.failure(IllegalStateException("Failed to write frame length prefix"))
        }

        val bodyWrite = session.connection.bulkTransfer(
            session.outEndpoint,
            payloadBytes,
            payloadBytes.size,
            timeoutMs
        )
        if (bodyWrite != payloadBytes.size) {
            return Result.failure(IllegalStateException("Failed to write full frame body"))
        }

        return Result.success(Unit)
    }

    fun startReading() {
        if (readJob?.isActive == true) {
            return
        }

        readJob = scope.launch {
            while (isActive) {
                val session = connectionManager.currentSession()
                if (session == null) {
                    delay(150L)
                    continue
                }

                val readResult = runCatching {
                    val lengthPrefix = readExact(session, 4)
                    val payloadLength = ByteBuffer
                        .wrap(lengthPrefix)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int

                    if (payloadLength <= 0 || payloadLength > 65_536) {
                        throw IllegalStateException("Invalid frame length: $payloadLength")
                    }

                    val payload = readExact(session, payloadLength)
                    val decoded = decoder.decode(payload.toString(Charsets.UTF_8))
                    if (decoded != null) {
                        messagesFlow.emit(decoded)
                    }
                }

                if (readResult.isFailure) {
                    delay(100L)
                }
            }
        }
    }

    fun stopReading() {
        readJob?.cancel()
        readJob = null
    }

    private fun readExact(session: UsbSession, count: Int): ByteArray {
        val timeoutMs = 2_000
        val result = ByteArray(count)
        var totalRead = 0

        while (totalRead < count) {
            val chunk = ByteArray(count - totalRead)
            val read = session.connection.bulkTransfer(
                session.inEndpoint,
                chunk,
                chunk.size,
                timeoutMs
            )
            if (read <= 0) {
                throw IllegalStateException("USB read timeout/disconnect while waiting for $count bytes")
            }
            chunk.copyInto(
                destination = result,
                destinationOffset = totalRead,
                startIndex = 0,
                endIndex = read
            )
            totalRead += read
        }

        return result
    }
}
