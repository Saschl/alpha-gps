package com.sasch.cameragps.sharednew.remote.wifi

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SonyArwImageTest {
    @Test
    fun decodesRealMediumPreviewAndAppliesTiffOrientation() = runTest {
        val prefix = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sony/DSC04175-preview-prefix.bin").use { it.readBytes() }
        verifySonyArwImage(prefix)
    }
}
