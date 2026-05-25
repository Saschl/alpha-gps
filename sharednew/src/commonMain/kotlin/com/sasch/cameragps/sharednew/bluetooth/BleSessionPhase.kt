package com.sasch.cameragps.sharednew.bluetooth

enum class BleSessionPhase {
    Disconnected,
    Connecting,
    Connected,
    DiscoveringServices,
    ReadingConfig,
    EnablingGps,
    LockingGps,
    SyncingTime,
    RemoteStatusProbing,
    Transmitting,
    Error,
}

