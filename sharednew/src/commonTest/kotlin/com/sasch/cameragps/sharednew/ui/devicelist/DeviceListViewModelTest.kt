package com.sasch.cameragps.sharednew.ui.devicelist

import androidx.lifecycle.ViewModelStore
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
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
