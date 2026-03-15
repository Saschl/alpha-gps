package com.saschl.cameragps.shared.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.darwin.NSObject
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import kotlin.coroutines.resume

/**
 * iOS Bluetooth controller backed by CoreBluetooth.
 *
 * Scanning publishes all discovered peripherals to [devices] as a [StateFlow].
 * Connect/disconnect bridge CoreBluetooth's delegate callbacks to suspend functions
 * via [suspendCancellableCoroutine].
 */
class IosBluetoothController : BluetoothController {

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    // UUID string -> CBPeripheral
    private val discovered = mutableMapOf<String, CBPeripheral>()
    private val connected  = mutableMapOf<String, CBPeripheral>()

    // Pending callbacks waiting for a connect/disconnect result
    private val connectCallbacks    = mutableMapOf<String, (Boolean) -> Unit>()
    private val disconnectCallbacks = mutableMapOf<String, () -> Unit>()

    // ---------------------------------------------------------------------------
    // CoreBluetooth delegate
    // ---------------------------------------------------------------------------
    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            discovered[id] = didDiscoverPeripheral
            refreshDeviceList()
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral,
        ) {
            val id = didConnectPeripheral.identifier.UUIDString
            connected[id] = didConnectPeripheral
            connectCallbacks.remove(id)?.invoke(true)
            refreshDeviceList()
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val id = didFailToConnectPeripheral.identifier.UUIDString
            connectCallbacks.remove(id)?.invoke(false)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            connected.remove(id)
            connectCallbacks.remove(id)?.invoke(false)
            disconnectCallbacks.remove(id)?.invoke()
            refreshDeviceList()
        }
    }

    private val central = CBCentralManager(delegate = delegate, queue = null)

    // ---------------------------------------------------------------------------
    // BluetoothController implementation
    // ---------------------------------------------------------------------------

    override suspend fun startScan() {
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
        }
        // If BT is off/not ready the delegate's centralManagerDidUpdateState will trigger scanning
        // once the adapter becomes powered on.
    }

    override suspend fun stopScan() {
        central.stopScan()
    }

    override suspend fun connect(identifier: String): Boolean {
        val peripheral = discovered[identifier] ?: return false
        if (connected.containsKey(identifier)) return true

        return suspendCancellableCoroutine { cont ->
            connectCallbacks[identifier] = { success ->
                if (cont.isActive) cont.resume(success)
            }
            central.connectPeripheral(peripheral, options = null)

            cont.invokeOnCancellation {
                connectCallbacks.remove(identifier)
                central.cancelPeripheralConnection(peripheral)
            }
        }
    }

    override suspend fun disconnect(identifier: String) {
        val peripheral = connected[identifier] ?: discovered[identifier] ?: return

        suspendCancellableCoroutine<Unit> { cont ->
            disconnectCallbacks[identifier] = {
                if (cont.isActive) cont.resume(Unit)
            }
            central.cancelPeripheralConnection(peripheral)

            cont.invokeOnCancellation {
                disconnectCallbacks.remove(identifier)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun refreshDeviceList() {
        _devices.update {
            discovered.map { (id, peripheral) ->
                BluetoothDeviceInfo(
                    identifier = id,
                    name = peripheral.name ?: "Unknown Device",
                    isConnected = connected.containsKey(id),
                )
            }
        }
    }
}
