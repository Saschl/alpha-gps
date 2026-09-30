package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class IosCameraImageStoreTest {
    @Test
    fun creationReturnsOwnershipEvenWhenCallerIsCancelled() = runTest {
        var destination: CameraImageDestination? = null
        val creator = launch {
            currentCoroutineContext().cancel()
            destination = IosCameraImageStore().create(SonyImageInfo(3, "test.jpg", "image/jpeg"))
        }
        creator.join()
        try {
            assertNotNull(destination)
        } finally {
            destination?.abort()
        }
    }
}
