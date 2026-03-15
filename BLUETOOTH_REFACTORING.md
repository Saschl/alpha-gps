# Bluetooth GATT Callback Refactoring

## Overview
The `BluetoothGattCallback` logic has been refactored from a monolithic inner class into a modular, separated-concerns architecture using specialized handler classes.

## Architecture

### Handler Classes

#### 1. **ConnectionStateHandler**
- **Responsibility**: Handles Bluetooth connection state changes
- **Key Methods**: 
  - `handleConnectionStateChange()` - Processes connection/disconnection events
- **Actions**:
  - Pauses/resumes devices in CameraConnectionManager
  - Triggers success/failure callbacks
  - Reads location enabled characteristic on successful connection

#### 2. **ServiceDiscoveryHandler**
- **Responsibility**: Handles GATT service discovery and initial setup
- **Key Methods**:
  - `handleServicesDiscovered()` - Processes discovered services
  - `processServiceDiscovery()` - Determines timezone/DST capability
- **Actions**:
  - Sets write characteristics in CameraConnectionManager
  - Checks timezone/DST support from database
  - Triggers GPS enablement or characteristic reads as needed

#### 3. **GpsEnablementHandler**
- **Responsibility**: Manages the GPS unlock/lock sequence for Sony cameras
- **Key Methods**:
  - `enableGpsTransmission()` - Initiates GPS enablement
  - `handleGpsUnlockWritten()` - Processes unlock confirmation
  - `handleGpsLockWritten()` - Processes lock confirmation and starts transmission
- **Actions**:
  - Writes GPS unlock/lock commands
  - Triggers location transmission start

#### 4. **CharacteristicReadHandler**
- **Responsibility**: Handles characteristic read operations
- **Key Methods**:
  - `handleCharacteristicRead()` - Processes read characteristic data
  - `hasTimeZoneDstFlag()` - Detects timezone/DST support
- **Actions**:
  - Parses timezone/DST capability from characteristic data
  - Updates LocationDataConfig
  - Persists capability to database
  - Triggers GPS enablement

#### 5. **CharacteristicWriteHandler**
- **Responsibility**: Handles characteristic write confirmations
- **Key Methods**:
  - `handleCharacteristicWrite()` - Routes write confirmations
- **Actions**:
  - Delegates to GpsEnablementHandler based on characteristic UUID
  - Logs write errors

#### 6. **GattCallbackCoordinator** (Main Coordinator)
- **Responsibility**: Coordinates all handlers and implements BluetoothGattCallback
- **Key Methods**: All BluetoothGattCallback overrides
- **Actions**:
  - Initializes all handler instances
  - Routes callback events to appropriate handlers
  - Manages handler dependencies

## Benefits of Modular Approach

### 1. **Separation of Concerns**
Each handler has a single, well-defined responsibility:
- Connection management
- Service discovery
- GPS enablement sequence
- Characteristic I/O

### 2. **Testability**
- Each handler can be unit tested independently
- Mock dependencies easily injected
- Clear input/output contracts

### 3. **Maintainability**
- Easier to locate and fix bugs
- Changes to one feature don't affect others
- Clear code organization

### 4. **Reusability**
- Handlers can be reused in different contexts
- Common logic centralized in single location

### 5. **Readability**
- Smaller, focused classes
- Less cognitive load when reading code
- Clear naming indicates purpose

## Data Flow

```
onConnectionStateChange
  └─> ConnectionStateHandler
       └─> onConnectionSuccess callback
            └─> resumeLocationTransmission()

onMtuChanged
  └─> discoverServices()

onServicesDiscovered
  └─> ServiceDiscoveryHandler
       ├─> Check timezone/DST from DB
       │    ├─> If known: GPS Enablement
       │    └─> If unknown: Read characteristic
       └─> Set write characteristic in CameraConnectionManager

onCharacteristicRead
  └─> CharacteristicReadHandler
       ├─> Parse timezone/DST capability
       ├─> Update config
       ├─> Save to DB
       └─> Trigger GPS Enablement

GPS Enablement
  └─> GpsEnablementHandler
       ├─> Write unlock command
       ├─> Write lock command
       └─> Start location transmission

onCharacteristicWrite
  └─> CharacteristicWriteHandler
       └─> Delegate to GpsEnablementHandler
            └─> Progress through unlock/lock sequence
```

## Migration Notes

### Before (Monolithic)
```kotlin
private inner class BluetoothGattCallbackHandler : BluetoothGattCallback() {
    // 250+ lines of mixed concerns
    override fun onConnectionStateChange(...) { /* ... */ }
    override fun onServicesDiscovered(...) { /* ... */ }
    override fun onCharacteristicWrite(...) { /* ... */ }
    override fun onCharacteristicRead(...) { /* ... */ }
    private fun enableGpsTransmission(...) { /* ... */ }
    private fun handleGpsEnableResponse(...) { /* ... */ }
    // ... more methods
}
```

### After (Modular)
```kotlin
// LocationSenderService.kt
private val bluetoothGattCallback by lazy {
    GattCallbackCoordinator(
        cameraConnectionManager = cameraConnectionManager,
        deviceDao = deviceDao,
        coroutineScope = lifecycleScope,
        onLocationConfigUpdate = { config -> locationDataConfig = config },
        onConnectionSuccess = { gatt -> resumeLocationTransmission(gatt) },
        onConnectionFailure = { cancelLocationTransmission() },
        onTransmissionStart = { startLocationTransmission() }
    )
}

// 5 separate handler files with clear responsibilities
```

## File Structure

```
service/
├── LocationSenderService.kt
└── handlers/
    ├── GattCallbackCoordinator.kt         (Main coordinator)
    ├── ConnectionStateHandler.kt          (Connection management)
    ├── ServiceDiscoveryHandler.kt         (Service discovery)
    ├── GpsEnablementHandler.kt            (GPS unlock/lock)
    ├── CharacteristicReadHandler.kt       (Read operations)
    └── CharacteristicWriteHandler.kt      (Write confirmations)
```

## Future Enhancements

1. **Error Recovery**: Add dedicated error handling and retry logic per handler
2. **State Management**: Consider adding explicit state machines for complex flows
3. **Metrics**: Add telemetry/analytics hooks in each handler
4. **Configuration**: Make handler behavior configurable via dependency injection
5. **Testing**: Add comprehensive unit tests for each handler

