package com.sasch.cameragps.sharednew

import kotlin.test.Test
import kotlin.test.assertTrue

class ScreenshotMockDataTest {
    @Test
    fun screenshotDevicesContainAtMostFiveCameras() {
        assertTrue(mockDevices.size in 1..5, "Screenshot mode must show at most five cameras")
    }

    @Test
    fun screenshotDevicesOnlyPopulateSavedSection() {
        assertTrue(mockDevices.isNotEmpty())
        assertTrue(mockDevices.all { it.isSaved }, "Screenshot mode must not show nearby cameras")
    }
}
