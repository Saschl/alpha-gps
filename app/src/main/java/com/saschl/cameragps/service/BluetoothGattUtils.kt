package com.saschl.cameragps.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import timber.log.Timber

/**
 * Utility class for Bluetooth GATT operations
 */
object BluetoothGattUtils {

    /**
     * Writes a characteristic value with proper API level handling
     */
    @SuppressLint("MissingPermission")
    fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(characteristic, value, writeType)
            // 201 == device busy, spams sentry, but I do not know the cause yet
            if (result != 0 && result != 201) {
                Timber.e("Writing characteristic failed. Result: $result")

                false
            } else {
                Timber.d("Characteristic written successfully (API 33+)")
                true
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            val result = gatt.writeCharacteristic(characteristic)
            if (!result) {
                Timber.e("Writing characteristic failed (legacy API)")
            }
            result
        }
    }

    /**
     * Finds a characteristic by UUID in the GATT services
     */
    fun findCharacteristic(gatt: BluetoothGatt, characteristicUuid: java.util.UUID): BluetoothGattCharacteristic? {
        return gatt.services?.flatMap { service -> service.characteristics }
            ?.find { it.uuid == characteristicUuid }
    }

    @SuppressLint("MissingPermission")
    fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, value)
            if (result != 0 && result != 201) {
                Timber.e("Writing descriptor failed. Result: $result")
                false
            } else {
                Timber.d("Descriptor written successfully (API 33+)")
                true
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            val result = gatt.writeDescriptor(descriptor)
            if (!result) {
                Timber.e("Writing descriptor failed (legacy API)")
            }
            result
        }
    }
}
