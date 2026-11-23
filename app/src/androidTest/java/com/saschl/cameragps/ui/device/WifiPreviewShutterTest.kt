package com.saschl.cameragps.ui.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.sasch.cameragps.sharednew.ui.remote.WifiPreviewShutterButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WifiPreviewShutterTest {
    @get:Rule
    val compose = createComposeRule()
    private val shooting = mutableStateOf(false)
    private val visible = mutableStateOf(true)
    private var captures = 0
    private var holds = 0
    private var releases = 0

    private fun show(acceptHold: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                if (visible.value) WifiPreviewShutterButton(
                    enabled = !shooting.value, canHold = true,
                    shooting = shooting.value, exposing = shooting.value,
                    label = "Shutter", holdLabel = "Hold shutter",
                    onCapture = { captures++ },
                    onHoldStart = {
                        holds++
                        if (acceptHold) shooting.value = true
                        acceptHold
                    },
                    onHoldEnd = { releases++; shooting.value = false },
                    modifier = Modifier.testTag("shutter"),
                )
            }
        }
    }

    private fun startHold() {
        compose.onNodeWithTag("shutter").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            assertEquals(1, holds)
            assertEquals(0, captures)
            assertEquals(0, releases)
        }
    }

    @Test
    fun tapStillTakesOneOrdinaryPhoto() {
        show()
        compose.onNodeWithTag("shutter").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, captures)
            assertEquals(0, holds)
            assertEquals(0, releases)
        }
    }

    @Test
    fun holdSurvivesBusyRecompositionAndReleaseDoesNotTakeAnotherPhoto() {
        show()
        startHold()
        compose.onNodeWithTag("shutter").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, releases)
            assertEquals(0, captures)
        }
    }

    @Test
    fun cancelledTouchReleasesTheShutter() {
        show()
        startHold()
        compose.onNodeWithTag("shutter").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(1, releases) }
    }

    @Test
    fun movingOffButtonReleasesTheShutter() {
        show()
        startHold()
        compose.onNodeWithTag("shutter").performTouchInput {
            moveTo(Offset(-200f, -200f))
            up()
        }
        compose.runOnIdle {
            assertEquals(1, releases)
            assertEquals(0, captures)
        }
    }

    @Test
    fun leavingPreviewReleasesExactlyOnce() {
        show()
        startHold()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertEquals(1, releases) }
    }

    @Test
    fun rejectedHoldNeverReleasesAnotherExposureOrFallsBackToTap() {
        show(acceptHold = false)
        compose.onNodeWithTag("shutter").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, holds)
            assertEquals(0, captures)
            assertEquals(0, releases)
        }
    }

    @Test
    fun busyShutterIgnoresTouch() {
        shooting.value = true
        show()
        compose.onNodeWithTag("shutter").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(0, holds)
            assertEquals(0, releases)
            assertEquals(0, captures)
        }
    }
}
