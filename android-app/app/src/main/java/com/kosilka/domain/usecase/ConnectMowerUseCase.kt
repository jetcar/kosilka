package com.kosilka.domain.usecase

import android.util.Log
import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.core.UiEvent
import com.kosilka.core.UiEventBus
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class ConnectMowerUseCase @Inject constructor(
    private val mowerDevice: MowerDevice,
    private val messageIdGenerator: MessageIdGenerator,
    private val dispatchers: CoroutineDispatchers,
    private val uiEventBus: UiEventBus
) {
    private companion object {
        const val TAG = "ConnectMowerUseCase"
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val pairResponseChannel = Channel<IncomingMessage.PairResponse>(capacity = Channel.BUFFERED)
    private val sessionAckChannel = Channel<IncomingMessage.SessionAck>(capacity = Channel.BUFFERED)
    private val errorChannel = Channel<IncomingMessage.ErrorMessage>(capacity = Channel.BUFFERED)

    private var incomingRouterJob: Job? = null
    private var connectionEventsJob: Job? = null
    private var heartbeatJob: Job? = null
    private var heartbeatMonitorJob: Job? = null

    @Volatile
    private var activeSessionId: String = ""

    @Volatile
    private var lastHeartbeatResponseAtMs: Long = 0L

    @Volatile
    private var nowProvider: () -> Long = { System.currentTimeMillis() }

    internal fun setNowProviderForTest(provider: () -> Long) {
        nowProvider = provider
    }

    private fun nowMs(): Long = nowProvider()

    suspend fun connect() {
        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            return
        }

        _connectionState.value = ConnectionState.Connecting
        messageIdGenerator.reset()
        clearChannels()

        val transportConnect = mowerDevice.connect()
        if (transportConnect.isFailure) {
            _connectionState.value = ConnectionState.Failed(
                transportConnect.exceptionOrNull()?.message ?: "Failed to open mower transport"
            )
            return
        }

        startEventRouterIfNeeded()
        val negotiatedSessionId = "app-session-${nowMs()}"
        activeSessionId = negotiatedSessionId

        val helloResult = mowerDevice.send(
            envelope(
                messageType = ProtocolConstants.TYPE_HELLO,
                sessionId = negotiatedSessionId,
                payload = mapOf("client" to "android-app")
            )
        )
        if (helloResult.isFailure) {
            _connectionState.value = ConnectionState.Failed("Failed to send HELLO")
            safeDisconnectInternal()
            return
        }

        var paired = false
        var unauthorizedFailure = false

        repeat(3) {
            if (paired || unauthorizedFailure) {
                return@repeat
            }

            mowerDevice.send(
                envelope(
                    messageType = ProtocolConstants.TYPE_PAIR_REQUEST,
                    sessionId = negotiatedSessionId,
                    payload = mapOf("requestedBy" to "android-app")
                )
            )

            when (awaitPairResponseOrUnauthorized(timeoutMs = 3_000L)) {
                PairingResult.Accepted -> paired = true
                PairingResult.Unauthorized -> unauthorizedFailure = true
                PairingResult.Timeout -> Unit
            }
        }

        if (!paired) {
            _connectionState.value = if (unauthorizedFailure) {
                ConnectionState.Failed("Authentication failed (ERR_UNAUTHORIZED)")
            } else {
                ConnectionState.Failed("PAIR_REQUEST timed out after 3 attempts")
            }
            safeDisconnectInternal()
            return
        }

        val sessionStartSent = mowerDevice.send(
            envelope(
                messageType = ProtocolConstants.TYPE_SESSION_START,
                sessionId = negotiatedSessionId,
                payload = mapOf("requestedAtMs" to nowMs())
            )
        )
        if (sessionStartSent.isFailure) {
            _connectionState.value = ConnectionState.Failed("Failed to send SESSION_START")
            safeDisconnectInternal()
            return
        }

        when (awaitSessionAckOrUnauthorized(timeoutMs = 3_000L)) {
            SessionAckResult.Acked -> {
                _connectionState.value = ConnectionState.Connected(negotiatedSessionId)
                lastHeartbeatResponseAtMs = nowMs()
                startHeartbeatLoop()
                startHeartbeatMonitorLoop()
            }

            SessionAckResult.Unauthorized -> {
                _connectionState.value = ConnectionState.Failed("SESSION_START unauthorized")
                safeDisconnectInternal()
            }

            SessionAckResult.Timeout -> {
                _connectionState.value = ConnectionState.Failed("SESSION_ACK timeout")
                safeDisconnectInternal()
            }
        }
    }

    suspend fun disconnectByUser() {
        if (_connectionState.value !is ConnectionState.Connected) {
            safeDisconnectInternal()
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        mowerDevice.send(
            envelope(
                messageType = ProtocolConstants.TYPE_RANGING_STOP,
                sessionId = activeSessionId,
                payload = mapOf("reason" to "user_request")
            )
        )

        safeDisconnectInternal()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun startEventRouterIfNeeded() {
        if (incomingRouterJob?.isActive == true && connectionEventsJob?.isActive == true) {
            return
        }

        incomingRouterJob?.cancel()
        incomingRouterJob = scope.launch {
            mowerDevice.incomingMessages.collect { message ->
                when (message) {
                    is IncomingMessage.PairResponse -> pairResponseChannel.trySend(message)
                    is IncomingMessage.SessionAck -> sessionAckChannel.trySend(message)
                    is IncomingMessage.ErrorMessage -> {
                        errorChannel.trySend(message)
                        Log.e(
                            TAG,
                            "firmware_error code=${message.code} name=${message.name} failedMessageId=${message.failedMessageId}"
                        )
                        uiEventBus.emit(
                            UiEvent.Snackbar(
                                message = "Firmware error: ${message.name} (${message.code})"
                            )
                        )
                    }
                    is IncomingMessage.Heartbeat -> lastHeartbeatResponseAtMs = nowMs()
                    else -> Unit
                }
            }
        }

        connectionEventsJob?.cancel()
        connectionEventsJob = scope.launch {
            mowerDevice.connectionEvents.collect { event ->
                when (event) {
                    is ConnectionEvent.Connected -> {
                        // Connect state is managed by handshake completion.
                    }

                    is ConnectionEvent.Disconnected -> {
                        stopHeartbeatLoops()
                        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }

                    is ConnectionEvent.Error -> {
                        stopHeartbeatLoops()
                        _connectionState.value = ConnectionState.Failed(event.message)
                    }
                }
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                val heartbeatSent = mowerDevice.send(
                    envelope(
                        messageType = ProtocolConstants.TYPE_HEARTBEAT,
                        sessionId = activeSessionId,
                        payload = mapOf("status" to "alive")
                    )
                )
                if (heartbeatSent.isFailure) {
                    _connectionState.value = ConnectionState.Failed("Heartbeat send failed")
                    safeDisconnectInternal()
                    break
                }
                delay(1_000L)
            }
        }
    }

    private fun startHeartbeatMonitorLoop() {
        heartbeatMonitorJob?.cancel()
        heartbeatMonitorJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                val now = nowMs()
                if (now - lastHeartbeatResponseAtMs > 3_000L) {
                    _connectionState.value = ConnectionState.Failed("Connection lost: 3 missed heartbeats")
                    safeDisconnectInternal()
                    break
                }
                delay(250L)
            }
        }
    }

    private suspend fun awaitPairResponseOrUnauthorized(timeoutMs: Long): PairingResult {
        val received = withTimeoutOrNull(timeoutMs) {
            select<PairingResult> {
                pairResponseChannel.onReceive { pairResponse ->
                    if (pairResponse.accepted) PairingResult.Accepted else PairingResult.Timeout
                }
                errorChannel.onReceive { error ->
                    if (error.code == ProtocolConstants.ERR_UNAUTHORIZED) {
                        PairingResult.Unauthorized
                    } else {
                        PairingResult.Timeout
                    }
                }
            }
        }
        return received ?: PairingResult.Timeout
    }

    private suspend fun awaitSessionAckOrUnauthorized(timeoutMs: Long): SessionAckResult {
        val received = withTimeoutOrNull(timeoutMs) {
            select<SessionAckResult> {
                sessionAckChannel.onReceive { sessionAck ->
                    if (sessionAck.ok) SessionAckResult.Acked else SessionAckResult.Timeout
                }
                errorChannel.onReceive { error ->
                    if (error.code == ProtocolConstants.ERR_UNAUTHORIZED) {
                        SessionAckResult.Unauthorized
                    } else {
                        SessionAckResult.Timeout
                    }
                }
            }
        }
        return received ?: SessionAckResult.Timeout
    }

    private fun envelope(
        messageType: String,
        sessionId: String,
        payload: Map<String, Any?>
    ): Envelope = Envelope(
        protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
        messageType = messageType,
        messageId = messageIdGenerator.next(),
        sessionId = sessionId,
        timestampMs = nowMs(),
        payload = payload
    )

    private fun clearChannels() {
        while (pairResponseChannel.tryReceive().isSuccess) {
            // drain stale values from previous sessions
        }
        while (sessionAckChannel.tryReceive().isSuccess) {
            // drain stale values from previous sessions
        }
        while (errorChannel.tryReceive().isSuccess) {
            // drain stale values from previous sessions
        }
    }

    private suspend fun safeDisconnectInternal() {
        stopHeartbeatLoops()
        heartbeatJob?.cancelAndJoin()
        heartbeatMonitorJob?.cancelAndJoin()
        mowerDevice.disconnect()
    }

    private fun stopHeartbeatLoops() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatMonitorJob?.cancel()
        heartbeatMonitorJob = null
    }

    private enum class PairingResult {
        Accepted,
        Unauthorized,
        Timeout
    }

    private enum class SessionAckResult {
        Acked,
        Unauthorized,
        Timeout
    }
}
