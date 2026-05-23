package com.kosilka.data.device.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.kosilka.core.CoroutineDispatchers
import com.kosilka.data.device.ConnectionEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@Singleton
class UsbConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 8)
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var receiverRegistered = false
    private var session: UsbSession? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        if (openSessionForDevice(device).isSuccess) {
                            emitConnectionEvent(ConnectionEvent.Connected(sessionId = sessionIdFor(device)))
                        } else {
                            emitConnectionEvent(ConnectionEvent.Error("Failed to open USB device session"))
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    closeSession()
                    emitConnectionEvent(ConnectionEvent.Disconnected)
                }
            }
        }
    }

    val connectionEvents: Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()

    suspend fun connect(): Result<Unit> {
        registerReceiverIfNeeded()

        val connectedDevice = usbManager.deviceList.values.firstOrNull()
        if (connectedDevice == null) {
            connectionEventsFlow.emit(ConnectionEvent.Disconnected)
            return Result.failure(IllegalStateException("No USB device is currently attached"))
        }

        val openResult = openSessionForDevice(connectedDevice)
        if (openResult.isFailure) {
            connectionEventsFlow.emit(ConnectionEvent.Error("Failed to open USB device session", openResult.exceptionOrNull()))
            return Result.failure(openResult.exceptionOrNull() ?: IllegalStateException("Failed to open session"))
        }

        connectionEventsFlow.emit(ConnectionEvent.Connected(sessionIdFor(connectedDevice)))
        return Result.success(Unit)
    }

    suspend fun disconnect() {
        closeSession()
        unregisterReceiverIfNeeded()
        connectionEventsFlow.emit(ConnectionEvent.Disconnected)
    }

    fun currentSession(): UsbSession? = session

    private fun emitConnectionEvent(event: ConnectionEvent) {
        scope.launch {
            connectionEventsFlow.emit(event)
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) {
            return
        }

        runCatching {
            context.unregisterReceiver(usbReceiver)
        }
        receiverRegistered = false
    }

    private fun sessionIdFor(device: UsbDevice): String = "usb-${device.deviceId}"

    private fun openSessionForDevice(device: UsbDevice): Result<Unit> {
        return runCatching {
            closeSession()

            if (!usbManager.hasPermission(device)) {
                throw IllegalStateException("USB permission missing for deviceId=${device.deviceId}")
            }

            val usbInterface = findBulkInterface(device)
                ?: throw IllegalStateException("No bulk USB interface found")

            val inEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
                ?: throw IllegalStateException("No bulk IN endpoint found")
            val outEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
                ?: throw IllegalStateException("No bulk OUT endpoint found")

            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("Failed to open UsbDeviceConnection")

            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IllegalStateException("Failed to claim USB interface")
            }

            session = UsbSession(
                device = device,
                usbInterface = usbInterface,
                connection = connection,
                inEndpoint = inEndpoint,
                outEndpoint = outEndpoint
            )
        }
    }

    private fun closeSession() {
        val current = session ?: return
        runCatching {
            current.connection.releaseInterface(current.usbInterface)
            current.connection.close()
        }
        session = null
    }

    private fun findBulkInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            val hasBulk = (0 until candidate.endpointCount).any { endpointIndex ->
                candidate.getEndpoint(endpointIndex).type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }
            if (hasBulk) {
                return candidate
            }
        }
        return null
    }

    private fun findEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == direction
            ) {
                return endpoint
            }
        }
        return null
    }
}

data class UsbSession(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val connection: UsbDeviceConnection,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint
)
