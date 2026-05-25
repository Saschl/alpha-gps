package com.saschl.cameragps.service.coordinator

import android.bluetooth.BluetoothGatt
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import com.saschl.cameragps.service.BluetoothGattUtils
import com.saschl.cameragps.service.CameraConnectionManager
import java.util.UUID

/**
 * Android implementation of [BleGattPort]. Bridges the shared coordinator's
 * platform-agnostic calls to Android BluetoothGatt operations via
 * [CameraConnectionManager] and [BluetoothGattUtils].
 */
class AndroidBleGattPort(
    private val connectionManager: CameraConnectionManager,
) : BleGattPort {

    override fun writeCharacteristic(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean {
        val connection = connectionManager.getConnection(identifier) ?: return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        return BluetoothGattUtils.writeCharacteristic(connection.gatt, characteristic, value)
    }

    override fun subscribeToNotifications(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        val connection = connectionManager.getConnection(identifier) ?: return false
        val statusChar = connection.remoteStatusCharacteristic ?: return false
        val descriptor = connection.remoteStatusDescriptor ?: return false

        val notifyEnabled = connection.gatt.setCharacteristicNotification(statusChar, true)
        if (!notifyEnabled) return false

        return BluetoothGattUtils.writeDescriptor(
            connection.gatt,
            descriptor,
            android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        )
    }

    override fun isConnected(identifier: String): Boolean {
        val connection = connectionManager.getConnection(identifier) ?: return false
        return connection.state == BluetoothGatt.GATT_SUCCESS
    }

    override fun hasRemoteControlCharacteristic(identifier: String): Boolean {
        val connection = connectionManager.getConnection(identifier) ?: return false
        return connection.remoteControlCharacteristic != null
    }

    override fun isRemoteFeatureActive(identifier: String): Boolean {
        val connection = connectionManager.getConnection(identifier) ?: return false
        return connection.remoteFeatureActive
    }

    override fun setRemoteFeatureActive(identifier: String, active: Boolean) {
        connectionManager.setRemoteFeatureActive(identifier, active)
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun readCharacteristic(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        val connection = connectionManager.getConnection(identifier) ?: return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        return connection.gatt.readCharacteristic(characteristic)
    }

    override fun hasCharacteristic(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        val connection = connectionManager.getConnection(identifier) ?: return false
        return findCharacteristic(connection.gatt, characteristicUuid) != null
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        uuid: String,
    ): android.bluetooth.BluetoothGattCharacteristic? {
        val target = UUID.fromString(uuid)
        return gatt.services?.flatMap { it.characteristics }?.find { it.uuid == target }
    }
}


