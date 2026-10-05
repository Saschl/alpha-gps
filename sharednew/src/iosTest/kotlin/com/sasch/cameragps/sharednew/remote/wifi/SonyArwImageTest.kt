@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.cinterop.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.Test

class SonyArwImageTest {
    @Test
    fun decodesRealMediumPreviewAndAppliesTiffOrientation() = runTest {
        val data = checkNotNull(NSData.dataWithContentsOfFile(SonyArwTestFixture.path))
        val prefix = checkNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt())
        verifySonyArwImage(prefix)
    }
}
