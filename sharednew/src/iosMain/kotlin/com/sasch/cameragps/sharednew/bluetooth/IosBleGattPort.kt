package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * iOS implementation of [BleGattPort]. Bridges the shared coordinator's
 * platform-agnostic calls to CoreBluetooth `CBPeripheral` operations.
 *
 * Requires a [SessionProvider] to look up the active session for a device identifier.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosBleGattPort(
    private val sessionProvider: SessionProvider,
) : BleGattPort {

    /**
     * Provides access to the per-device session data from [IosBluetoothController].
     */
    internal interface SessionProvider {
        fun getSession(identifier: String): IosBleSession?
    }

    /**
     * Minimal session snapshot needed by the port. [IosBluetoothController] maps
     * its internal [PeripheralSession] to this interface.
     */
    internal interface IosBleSession {
        val peripheral: CBPeripheral
        val remoteControlCharacteristic: platform.CoreBluetooth.CBCharacteristic?
        val remoteStatusCharacteristic: platform.CoreBluetooth.CBCharacteristic?
        val remoteStatusNotificationsEnabled: Boolean
        var remoteFeatureActive: Boolean
    }

    override fun writeCharacteristic(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean {
        val session = sessionProvider.getSession(identifier) ?: return false
        val targetUuid = CBUUID.UUIDWithString(characteristicUuid)

        // Find the characteristic by UUID from the peripheral's discovered services
        val characteristic = session.peripheral.services
            ?.flatMap {
                (it as platform.CoreBluetooth.CBService).characteristics?.toList() ?: emptyList()
            }
            ?.map { it as platform.CoreBluetooth.CBCharacteristic }
            ?.find { it.UUID == targetUuid }
            ?: return false

        session.peripheral.writeValue(
            data = value.toNSData(),
            forCharacteristic = characteristic,
            type = CBCharacteristicWriteWithResponse,
        )
        return true
    }

    override fun subscribeToNotifications(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        val session = sessionProvider.getSession(identifier) ?: return false
        val statusCharacteristic = session.remoteStatusCharacteristic ?: return false
        if (session.remoteStatusNotificationsEnabled) return true
        session.peripheral.setNotifyValue(true, forCharacteristic = statusCharacteristic)
        return true
    }

    override fun isConnected(identifier: String): Boolean {
        val session = sessionProvider.getSession(identifier) ?: return false
        return session.peripheral.state == CBPeripheralStateConnected
    }

    override fun hasRemoteControlCharacteristic(identifier: String): Boolean {
        val session = sessionProvider.getSession(identifier) ?: return false
        return session.remoteControlCharacteristic != null
    }

    override fun isRemoteFeatureActive(identifier: String): Boolean {
        val session = sessionProvider.getSession(identifier) ?: return false
        return session.remoteFeatureActive
    }

    override fun setRemoteFeatureActive(identifier: String, active: Boolean) {
        val session = sessionProvider.getSession(identifier) ?: return
        session.remoteFeatureActive = active
    }

    override fun readCharacteristic(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        val session = sessionProvider.getSession(identifier) ?: return false
        val targetUuid = CBUUID.UUIDWithString(characteristicUuid)
        val characteristic = session.peripheral.services
            ?.flatMap {
                (it as platform.CoreBluetooth.CBService).characteristics?.toList() ?: emptyList()
            }
            ?.map { it as platform.CoreBluetooth.CBCharacteristic }
            ?.find { it.UUID == targetUuid }
            ?: return false
        session.peripheral.readValueForCharacteristic(characteristic)
        return true
    }

    override fun hasCharacteristic(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        val session = sessionProvider.getSession(identifier) ?: return false
        val targetUuid = CBUUID.UUIDWithString(characteristicUuid)
        return session.peripheral.services
            ?.flatMap {
                (it as platform.CoreBluetooth.CBService).characteristics?.toList() ?: emptyList()
            }
            ?.any { (it as platform.CoreBluetooth.CBCharacteristic).UUID == targetUuid }
            ?: false
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}


