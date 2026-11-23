package com.sasch.cameragps.sharednew.ui.devicelist

import androidx.lifecycle.ViewModelStore
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.BulbTimerState
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListViewModelTest {
    @Test
    fun bluetoothTimerAvailabilityAndCountdownFollowTheCameraSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val ready = CameraSession("A", BleSessionPhase.Transmitting, remoteFeatureActive = true)
            val source = object : DeviceListDataSource {
                override val sessions = MutableStateFlow(mapOf("A" to ready))
                override val transmissionActive = MutableStateFlow(false)
                override val deviceSettings = MutableStateFlow(listOf(CameraDevice(mac = "A")))
            }
            val model = DeviceListViewModel(source)
            store.put("devices", model)
            backgroundScope.launch { model.items.collect() }
            runCurrent()
            fun item() = model.items.value.getValue("A")
            assertTrue(item().canStartBulbTimer)
            val countdown = BulbTimerState(BulbPhase.Exposing, 65)
            source.sessions.value = mapOf("A" to ready.copy(bulbTimer = countdown))
            runCurrent()
            assertEquals(countdown, item().bulbTimer)
            assertFalse(item().canStartBulbTimer)
            source.sessions.value = mapOf("A" to ready.copy(bulbTimer = countdown,
                wifiRemote = WifiRemoteState(phase = WifiRemotePhase.Ready)))
            runCurrent()
            assertEquals(BulbPhase.Idle, item().bulbTimer.phase)
            assertFalse(item().canStartBulbTimer)
            source.sessions.value = mapOf("A" to ready)
            source.deviceSettings.value = listOf(CameraDevice(mac = "A", deviceEnabled = false))
            runCurrent()
            assertFalse(item().canStartBulbTimer)
        } finally {
            store.clear()
            backgroundScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cameraWarningUpdatesIndependentlyOfTransmissionIndicator() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val source = object : DeviceListDataSource {
                override val sessions = MutableStateFlow(
                    mapOf(
                        "A" to CameraSession("A", BleSessionPhase.Transmitting),
                    )
                )
                override val transmissionActive = MutableStateFlow(true)
                override val deviceSettings = flowOf(listOf(CameraDevice(mac = "A")))
            }
            val model = DeviceListViewModel(source)
            store.put("devices", model)
            backgroundScope.launch { model.items.collect() }
            runCurrent()
            for (disabled in listOf(true, false, true)) {
                source.sessions.value = mapOf(
                    "A" to CameraSession(
                        "A",
                        BleSessionPhase.Transmitting,
                        locationDisabledByCamera = disabled
                    ),
                )
                runCurrent()
                val item = model.items.value.getValue("A")
                assertEquals(disabled, item.locationDisabledByCamera)
                assertTrue(item.isTransmissionActive)
            }
        } finally {
            store.clear()
            backgroundScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
