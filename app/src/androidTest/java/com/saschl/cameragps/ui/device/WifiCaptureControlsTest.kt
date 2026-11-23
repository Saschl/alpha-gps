package com.saschl.cameragps.ui.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.BulbTimerState
import com.sasch.cameragps.sharednew.remote.ShutterControls
import com.sasch.cameragps.sharednew.remote.wifi.WifiCaptureStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.ui.remote.WifiCaptureControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WifiCaptureControlsTest {
    @get:Rule
    val compose = createComposeRule()
    private val bulb = mutableStateOf(BulbTimerState())
    private val state = mutableStateOf(
        WifiRemoteState(
            phase = WifiRemotePhase.Ready, canCaptureStill = true,
            canCaptureBulb = true, canTransferImages = true,
        )
    )
    private var captures = 0
    private var starts = 0
    private var duration = 0
    private var stops = 0
    private var browses = 0

    private val shutter = object : ShutterControls {
        override fun capture(identifier: String): Boolean {
            captures++
            return true
        }

        override fun startBulbTimer(identifier: String, seconds: Int): Boolean {
            starts++
            duration = seconds
            bulb.value = BulbTimerState(BulbPhase.Starting)
            state.value = state.value.copy(capture = WifiCaptureStatus.Shooting)
            return true
        }

        override fun stopBulbTimer(identifier: String) { stops++ }
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                WifiCaptureControls(
                    identifier = "camera", state = state.value, bulb = bulb.value,
                    shutter = shutter,
                    onBrowsePhotos = { browses++ },
                )
            }
        }
    }

    @Test
    fun timerSetupIsHiddenAndNormalActionsRemainAvailable() {
        show()
        compose.onNodeWithText("Exposure seconds (1–3600)").assertDoesNotExist()
        compose.onNodeWithText("Start exposure").assertDoesNotExist()
        compose.onNodeWithText("Take photo").performClick()
        compose.onNodeWithText("Photos").performClick()
        compose.runOnIdle {
            assertEquals(1, captures)
            assertEquals(1, browses)
            assertEquals(0, starts)
        }
    }

    @Test
    fun dismissingSetupDoesNotStartOrStopAndRetainsDuration() {
        show()
        compose.onNodeWithText("Bulb timer").performClick()
        compose.onNodeWithText("Exposure seconds (1–3600)").performTextReplacement("15")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Bulb timer").performClick()
        compose.onNodeWithText("15").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(0, stops)
        }
    }

    @Test
    fun startingTimerDismissesSetupAndKeepsStopVisibleDuringCountdown() {
        show()
        compose.onNodeWithText("Bulb timer").performClick()
        compose.onNodeWithText("Exposure seconds (1–3600)").performTextReplacement("5")
        compose.onNodeWithText("Start exposure").performClick()
        compose.onNodeWithText("Exposure seconds (1–3600)").assertDoesNotExist()
        compose.onNodeWithText("Take photo").assertDoesNotExist()
        compose.onNodeWithText("Photos").assertIsNotEnabled()
        compose.onNodeWithText("Bulb timer").assertIsNotEnabled()
        compose.runOnIdle { bulb.value = BulbTimerState(BulbPhase.Exposing, 4) }
        compose.onNodeWithText("4 seconds remaining").assertIsDisplayed()
        compose.onNodeWithText("Stop exposure").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(5, duration)
            assertEquals(1, stops)
            assertEquals(0, captures)
        }
    }

    @Test
    fun invalidDurationCannotStartExposure() {
        show()
        compose.onNodeWithText("Bulb timer").performClick()
        for (invalid in listOf("", "0", "3601")) {
            compose.onNodeWithText("Exposure seconds (1–3600)").performTextReplacement(invalid)
            compose.onNodeWithText("Start exposure").assertIsNotEnabled()
        }
        compose.onNodeWithText("Exposure seconds (1–3600)").performTextReplacement("3600")
        compose.onNodeWithText("Start exposure").assertIsEnabled()
    }

    @Test
    fun processingBlocksActionsUntilCaptureCompletes() {
        bulb.value = BulbTimerState(BulbPhase.Processing)
        state.value = state.value.copy(capture = WifiCaptureStatus.Shooting)
        show()
        compose.onNodeWithText("Camera is processing…").assertIsDisplayed()
        compose.onNodeWithText("Stop exposure").assertIsNotEnabled()
        compose.onNodeWithText("Photos").assertIsNotEnabled()
        compose.runOnIdle {
            bulb.value = BulbTimerState(BulbPhase.Finished)
            state.value = state.value.copy(capture = WifiCaptureStatus.Captured)
        }
        compose.onNodeWithText("Take photo").assertIsEnabled()
        compose.onNodeWithText("Photos").assertIsEnabled()
        compose.onNodeWithText("Stop exposure").assertDoesNotExist()
    }
}
