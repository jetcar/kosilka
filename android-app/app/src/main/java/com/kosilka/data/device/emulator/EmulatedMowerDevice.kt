package com.kosilka.data.device.emulator

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.emulator.EmulatorScenarioEngine
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.CoverageSegmentPayload
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmulatedMowerDevice @Inject constructor(
    private val scenarioEngine: EmulatorScenarioEngine,
    private val dispatchers: CoroutineDispatchers
) : MowerDevice {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 8)
    private val internalMessagesFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    private val idCounter = AtomicLong(1L)

    private var heartbeatJob: Job? = null
    private var coverageJob: Job? = null

    private var connected = false
    private var sessionId: String = "emu-session"

    override val connectionEvents: Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = merge(
        scenarioEngine.messages,
        internalMessagesFlow.asSharedFlow()
    )

    override suspend fun connect(): Result<Unit> {
        connected = true
        sessionId = "emu-session-${System.currentTimeMillis()}"
        connectionEventsFlow.emit(ConnectionEvent.Connected(sessionId = sessionId))
        startHeartbeatLoop()
        startCoverageLoop()
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        if (!connected) {
            return
        }

        connected = false
        stopBackgroundLoops()
        scenarioEngine.stopRanging()
        connectionEventsFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        if (!connected) {
            return Result.failure(IllegalStateException("Emulator is not connected"))
        }

        return try {
            when (envelope.messageType) {
                ProtocolConstants.TYPE_PAIR_REQUEST -> {
                    emitPairResponse(envelope.sessionId)
                }

                ProtocolConstants.TYPE_SESSION_START -> {
                    sessionId = envelope.sessionId.ifBlank { sessionId }
                    internalMessagesFlow.emit(
                        IncomingMessage.SessionAck(
                            messageId = nextId(),
                            sessionId = sessionId,
                            timestampMs = System.currentTimeMillis(),
                            ok = true
                        )
                    )
                    connectionEventsFlow.emit(ConnectionEvent.Connected(sessionId))
                }

                ProtocolConstants.TYPE_HEARTBEAT -> {
                    internalMessagesFlow.emit(
                        IncomingMessage.Heartbeat(
                            messageId = nextId(),
                            sessionId = sessionId,
                            timestampMs = System.currentTimeMillis(),
                            status = "ok"
                        )
                    )
                }

                ProtocolConstants.TYPE_RANGING_START -> {
                    val sampleRateHz = (envelope.payload["sampleRateHz"] as? Int) ?: 5
                    scenarioEngine.startRanging(sessionId, sampleRateHz)
                }

                ProtocolConstants.TYPE_RANGING_STOP -> {
                    scenarioEngine.stopRanging()
                }

                ProtocolConstants.TYPE_MOVE_TO -> {
                    if (scenarioEngine.isBusyActive()) {
                        internalMessagesFlow.emit(
                            IncomingMessage.ErrorMessage(
                                messageId = nextId(),
                                sessionId = sessionId,
                                timestampMs = System.currentTimeMillis(),
                                code = ProtocolConstants.ERR_BUSY,
                                name = "ERR_BUSY",
                                detail = "Mower is busy in emulator scenario",
                                failedMessageId = envelope.messageId
                            )
                        )
                    }
                }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            connectionEventsFlow.emit(ConnectionEvent.Error("Failed to process emulator message", t))
            Result.failure(t)
        }
    }

    private suspend fun emitPairResponse(requestSessionId: String) {
        val effectiveSessionId = requestSessionId.ifBlank { sessionId }
        internalMessagesFlow.emit(
            IncomingMessage.PairResponse(
                messageId = nextId(),
                sessionId = effectiveSessionId,
                timestampMs = System.currentTimeMillis(),
                accepted = true,
                deviceInstanceId = "emu-device-1"
            )
        )
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && connected) {
                delay(1_000L)
                internalMessagesFlow.emit(
                    IncomingMessage.Heartbeat(
                        messageId = nextId(),
                        sessionId = sessionId,
                        timestampMs = System.currentTimeMillis(),
                        status = "ok"
                    )
                )
            }
        }
    }

    private fun startCoverageLoop() {
        coverageJob?.cancel()
        coverageJob = scope.launch {
            var previous = scenarioEngine.currentPosition()
            while (isActive && connected) {
                delay(1_000L)
                val current = scenarioEngine.currentPosition()
                if (current != previous) {
                    internalMessagesFlow.emit(
                        IncomingMessage.CoverageUpdate(
                            messageId = nextId(),
                            sessionId = sessionId,
                            timestampMs = System.currentTimeMillis(),
                            segments = listOf(
                                CoverageSegmentPayload(
                                    fromXMm = previous.xMm,
                                    fromYMm = previous.yMm,
                                    toXMm = current.xMm,
                                    toYMm = current.yMm
                                )
                            )
                        )
                    )
                }
                previous = current
            }
        }
    }

    private fun stopBackgroundLoops() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        coverageJob?.cancel()
        coverageJob = null
    }

    private fun nextId(): Long = idCounter.getAndIncrement()
}
