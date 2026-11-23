package com.saschl.cameragps

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.BulbTimerControls
import com.sasch.cameragps.sharednew.remote.BulbTimerState
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.sasch.cameragps.sharednew.ui.devicelist.SharedDeviceList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeviceListBulbTimerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val devices = (1..24).map { BluetoothDeviceInfo("CAM-$it", "Camera $it", isConnected = true, isSaved = true) }
    private val timers = mutableStateOf(mapOf<String, BulbTimerState>())
    private val durations = mutableMapOf("CAM-1" to 72, "CAM-24" to 33)
    private val stops = mutableListOf<String>()
    private val visible = mutableStateOf(true)
    private var leaveOnStart = false
    private val controls = object : BulbTimerControls {
        override fun startBulbTimer(identifier: String, seconds: Int): Boolean {
            if (leaveOnStart) visible.value = false
            else timers.value += identifier to BulbTimerState(BulbPhase.Exposing, seconds)
            return true
        }

        override fun stopBulbTimer(identifier: String) {
            stops += identifier
            timers.value += identifier to BulbTimerState(BulbPhase.Finished)
        }
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                if (visible.value) SharedDeviceList(
                    devices = devices,
                    items = devices.associate { device ->
                        val bulb = timers.value[device.identifier] ?: BulbTimerState()
                        val remote = device == devices.first() || device == devices.last()
                        device.identifier to DeviceListItem(device.identifier, null, false, true, remote,
                            isShutterActive = false, bulbTimer = bulb, canStartBulbTimer = remote && !bulb.active)
                    },
                    bulbControls = controls, lastBulbSeconds = { durations.getValue(it) },
                    onBulbDurationUsed = { id, seconds -> durations[id] = seconds },
                    onTriggerRemoteShutter = { error("Unexpected shutter tap") },
                    onOpenDetails = { error("Unexpected navigation") },
                    onConnect = {}, onDelete = {}, onOpenTroubleshooting = {},
                )
            }
        }
    }

    @Test
    fun startsForSelectedCameraAndKeepsCountdownWhileCardIsOffscreen() {
        show()
        compose.onNodeWithContentDescription("Bulb timer").performClick()
        compose.onNodeWithText("72").assertIsDisplayed()
        val duration = compose.onNodeWithText("Exposure seconds (1–3600)")
        duration.performTextReplacement("0")
        compose.onNodeWithText("Start exposure").assertIsNotEnabled()
        duration.performTextReplacement("65")
        compose.onNodeWithText("Start exposure").performClick()
        compose.onNodeWithText("1:05 remaining").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(devices.size)
        compose.runOnIdle {
            assertEquals(emptyList<String>(), stops)
            assertEquals(65, durations["CAM-1"])
        }
        compose.onNodeWithContentDescription("Bulb timer").performClick()
        compose.onNodeWithText("33").assertIsDisplayed()
        compose.onNodeWithText("Start exposure").performClick()
        compose.onNodeWithText("0:33 remaining").assertIsDisplayed()
        compose.onNodeWithText("Stop exposure").performClick()
        compose.runOnIdle { assertEquals(BulbPhase.Finished, timers.value.getValue("CAM-24").phase) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("1:05 remaining").assertIsDisplayed()
        compose.onNodeWithText("Stop exposure").performClick()
        compose.onNodeWithContentDescription("Bulb timer").performClick()
        compose.onNodeWithText("65").assertIsDisplayed()
    }

    @Test
    fun backgroundingTheListRequestsAnEarlyStop() {
        show()
        compose.onNodeWithContentDescription("Bulb timer").performClick()
        compose.onNodeWithText("Start exposure").performClick()
        compose.onNodeWithText("1:12 remaining").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(listOf("CAM-1"), stops)
    }

    @Test
    fun leavingImmediatelyAfterStartStopsBeforeFirstCountdownUpdate() {
        leaveOnStart = true
        show()
        compose.onNodeWithContentDescription("Bulb timer").performClick()
        compose.onNodeWithText("Start exposure").performClick()
        compose.runOnIdle { assertEquals(listOf("CAM-1"), stops) }
    }

    @Test
    fun leavingListStopsExposureEvenWhenItsCardIsOffscreen() {
        show()
        compose.onNodeWithContentDescription("Bulb timer").performClick()
        compose.onNodeWithText("Start exposure").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(devices.size)
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertEquals(listOf("CAM-1"), stops) }
    }
}
