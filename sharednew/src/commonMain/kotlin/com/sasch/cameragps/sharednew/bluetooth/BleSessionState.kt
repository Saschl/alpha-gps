package com.sasch.cameragps.sharednew.bluetooth

data class BleSessionState(
    val identifier: String,
    val phase: BleSessionPhase,
    val remoteFeatureActive: Boolean = false,
)

