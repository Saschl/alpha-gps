package com.sasch.cameragps.sharednew.bluetooth.coordinator

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared remote control coordinator. Owns:
 * - Remote feature status detection (pure byte-array parsing)
 * - Periodic probe loop (probe while feature inactive, stop when active)
 * - Shutter command orchestration
 *
 * Platform-specific BLE I/O is delegated to [BleGattPort].
 * Timing uses coroutines so it works identically on Android and iOS.
 */
class RemoteControlCoordinator(
    private val port: BleGattPort,
    private val scope: CoroutineScope,
) {
    private val activeProbeJobs = mutableMapOf<String, Job>()
    private val monitoredDevices = mutableSetOf<String>()

    private val _events = Channel<BleSessionEvent>(Channel.UNLIMITED)

    /** Collect this flow to receive remote-feature state change events. */
    val events: Flow<BleSessionEvent> = _events.receiveAsFlow()

    // ---- Public API called by platform GATT callbacks / service ----

    /**
     * Begin remote status probing for [identifier]. Subscribes to remote status
     * notifications and starts the probe loop (which polls while the feature is inactive).
     *
     * Call this after the BLE handshake is complete (GPS enabled, time synced, transmitting).
     */
    fun startRemoteStatusMonitoring(identifier: String) {
        val normalized = identifier.uppercase()
        monitoredDevices.add(normalized)

        // Subscribe to remote status characteristic notifications
        port.subscribeToNotifications(normalized, SonyBluetoothConstants.REMOTE_STATUS_UUID)

        if (!port.hasRemoteControlCharacteristic(normalized)) return

        startProbeLoop(normalized)
    }

    /**
     * Called when the remote status characteristic value changes (notification from camera).
     * Returns `true` if the platform should send a shutter-up command (specific response value).
     */
    fun onRemoteStatusChanged(identifier: String, value: ByteArray): Boolean {
        val normalized = identifier.uppercase()
        val wasActive = port.isRemoteFeatureActive(normalized)
        val active = isRemoteFeatureActive(value)
        port.setRemoteFeatureActive(normalized, active)

        if (active) {
            stopProbeLoop(normalized)
            if (!wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureActivated(normalized))
            }
        } else {
            if (wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureDeactivated(normalized))
            }
            if (normalized in monitoredDevices) {
                startProbeLoop(normalized)
            } else {
                stopProbeLoop(normalized)
            }
        }

        return shouldSendShutterUp(value)
    }

    /**
     * Called when a write to the remote control characteristic completes.
     * Emits activation/deactivation events based on success.
     */
    fun onRemoteControlWriteResponse(identifier: String, success: Boolean) {
        val normalized = identifier.uppercase()
        val wasActive = port.isRemoteFeatureActive(normalized)
        if (success) {
            port.setRemoteFeatureActive(normalized, true)
            stopProbeLoop(normalized)
            if (!wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureActivated(normalized))
            }
        } else {
            port.setRemoteFeatureActive(normalized, false)
            if (wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureDeactivated(normalized))
            }
        }
    }

    /**
     * Trigger a remote shutter press for [identifier].
     * Returns `true` if the shutter-down command was sent.
     * Shutter-up is sent later when the camera reports success via characteristic change callback.
     */
    fun handleRemoteShutterRequest(identifier: String): Boolean {
        val normalized = identifier.uppercase()

        if (!port.isConnected(normalized)) return false
        if (!port.isRemoteFeatureActive(normalized)) return false

        return port.writeCharacteristic(
            normalized,
            SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
            SonyBluetoothConstants.FULL_SHUTTER_DOWN_COMMAND,
        )
    }

    /**
     * Send the shutter-up command for [identifier].
     */
    fun sendShutterUp(identifier: String): Boolean {
        val normalized = identifier.uppercase()
        return port.writeCharacteristic(
            normalized,
            SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
            SonyBluetoothConstants.FULL_SHUTTER_UP_COMMAND,
        )
    }

    /**
     * Trigger a remote shutter action (down command only) for [identifier].
     *
     * The shared BLE callback flow sends shutter-up only after camera acknowledgement.
     */
    fun triggerRemoteShutter(identifier: String): Boolean {
        return handleRemoteShutterRequest(identifier)
    }

    /**
     * Cancel the probe loop for a single device (e.g. on disconnect).
     */
    fun cancelProbe(identifier: String) {
        val normalized = identifier.uppercase()
        monitoredDevices.remove(normalized)
        activeProbeJobs.remove(normalized)?.cancel()
    }

    /**
     * Cancel all active probe loops (e.g. on service destroy).
     */
    fun cancelAllProbes() {
        monitoredDevices.clear()
        activeProbeJobs.values.forEach { it.cancel() }
        activeProbeJobs.clear()
    }

    // ---- Internal probe loop ----

    private fun startProbeLoop(identifier: String) {
        if (activeProbeJobs.containsKey(identifier)) return
        if (!port.hasRemoteControlCharacteristic(identifier)) return

        val job = scope.launch {
            delay(REMOTE_STATUS_PROBE_INITIAL_DELAY_MS)
            while (isActive) {
                if (!port.isConnected(identifier)) {
                    break
                }
                if (!port.hasRemoteControlCharacteristic(identifier)) {
                    break
                }

                port.writeCharacteristic(
                    identifier,
                    SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
                    SonyBluetoothConstants.PROBE_COMMAND,
                )

                delay(REMOTE_STATUS_PROBE_INTERVAL_MS)
            }
            // Clean up our own entry when the loop exits naturally
            val currentJob = currentCoroutineContext()[Job]
            if (activeProbeJobs[identifier] === currentJob) {
                activeProbeJobs.remove(identifier)
            }
        }
        activeProbeJobs[identifier] = job
    }

    private fun stopProbeLoop(identifier: String) {
        activeProbeJobs.remove(identifier)?.cancel()
    }

    companion object {
        const val REMOTE_STATUS_PROBE_INTERVAL_MS = 3_000L
        const val REMOTE_STATUS_PROBE_INITIAL_DELAY_MS = 500L

        /**
         * Determine if the remote feature is active based on the characteristic value.
         * Shared between Android and iOS — the byte protocol is identical.
         */
        fun isRemoteFeatureActive(value: ByteArray): Boolean {
            if (value.isEmpty()) return false
            return !value.contentEquals(byteArrayOf(0x02, 0xC3.toByte(), 0x00))
        }

        /**
         * Check if this characteristic change value indicates the platform
         * should send a shutter-up command.
         */
        fun shouldSendShutterUp(value: ByteArray): Boolean {
            return value.contentEquals(byteArrayOf(0x02, 0xA0.toByte(), 0x00))
        }

        /**
         * Parse the config-read characteristic value for the timezone/DST flag.
         * Shared between Android and iOS.
         */
        fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
            return value.size >= 5 && (value[4].toInt() and 0x02) != 0
        }
    }
}

