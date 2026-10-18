package com.saschl.cameragps.service.coordinator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ServicePresenceCoordinatorTest {
    @Test
    fun `camera can stop advertising and keep transmitting until GATT disconnects`() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.onDeviceAppeared(CAMERA)
        fixture.connected.add(CAMERA)

        repeat(3) { fixture.coordinator.onDeviceDisappeared(CAMERA) }
        assertEquals(0, fixture.shutdowns)

        fixture.connected.clear()
        fixture.coordinator.onDeviceDisconnected(CAMERA)
        assertEquals(1, fixture.shutdowns)
    }

    @Test
    fun `GATT disconnect before disappearance waits for presence loss`() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.onDeviceAppeared(CAMERA)

        fixture.coordinator.onDeviceDisconnected(CAMERA)
        assertEquals(0, fixture.shutdowns)

        fixture.coordinator.onDeviceDisappeared(CAMERA)
        assertEquals(1, fixture.shutdowns)
    }

    @Test
    fun `disappearance without an established connection still stops an idle service`() =
        runBlocking {
            val fixture = Fixture()
            fixture.coordinator.onDeviceAppeared(CAMERA)

            fixture.coordinator.onDeviceDisappeared(CAMERA)

            assertEquals(1, fixture.shutdowns)
        }

    @Test
    fun `always on preserves reconnect after both disappearance and disconnect`() = runBlocking {
        val fixture = Fixture()
        fixture.alwaysOn = true
        fixture.connected.add(CAMERA)
        fixture.coordinator.onDeviceDisappeared(CAMERA)

        fixture.connected.clear()
        fixture.coordinator.onDeviceDisconnected(CAMERA)
        fixture.coordinator.onDeviceDisappeared(CAMERA)

        assertEquals(0, fixture.shutdowns)
    }

    @Test
    fun `another connected camera keeps service alive until its own disconnect`() = runBlocking {
        val fixture = Fixture()
        fixture.connected.addAll(listOf(CAMERA, OTHER_CAMERA))
        fixture.coordinator.onDeviceDisappeared(CAMERA)
        fixture.coordinator.onDeviceDisappeared(OTHER_CAMERA)

        fixture.connected.remove(CAMERA)
        fixture.coordinator.onDeviceDisconnected(CAMERA)
        assertEquals(0, fixture.shutdowns)

        fixture.connected.remove(OTHER_CAMERA)
        fixture.coordinator.onDeviceDisconnected(OTHER_CAMERA)
        assertEquals(1, fixture.shutdowns)
    }

    @Test
    fun `other camera with presence keeps its reconnect opportunity after first camera leaves`() =
        runBlocking {
            val fixture = Fixture()
            fixture.connected.addAll(listOf(CAMERA, OTHER_CAMERA))
            fixture.coordinator.onDeviceAppeared(OTHER_CAMERA)
            fixture.coordinator.onDeviceDisappeared(CAMERA)
            fixture.connected.remove(CAMERA)
            fixture.coordinator.onDeviceDisconnected(CAMERA)

            fixture.connected.remove(OTHER_CAMERA)
            fixture.coordinator.onDeviceDisconnected(OTHER_CAMERA)
            assertEquals(0, fixture.shutdowns)

            fixture.coordinator.onDeviceDisappeared(OTHER_CAMERA)
            assertEquals(1, fixture.shutdowns)
        }

    @Test
    fun `reappearance clears prior disappearance regardless of address case`() = runBlocking {
        val fixture = Fixture()
        fixture.connected.add(CAMERA)
        fixture.coordinator.onDeviceDisappeared(CAMERA.lowercase())
        fixture.coordinator.onDeviceAppeared(CAMERA)
        fixture.connected.clear()

        fixture.coordinator.onDeviceDisconnected(CAMERA.lowercase())

        assertEquals(0, fixture.shutdowns)
    }

    @Test
    fun `disconnect matches a disappearance reported with different address case`() = runBlocking {
        val fixture = Fixture()
        fixture.connected.add(CAMERA)
        fixture.coordinator.onDeviceDisappeared(CAMERA.lowercase())
        fixture.connected.clear()

        fixture.coordinator.onDeviceDisconnected(CAMERA)

        assertEquals(1, fixture.shutdowns)
    }

    @Test
    fun `delayed disconnect cannot stop an already reconnected camera`() = runBlocking {
        val fixture = Fixture()
        fixture.connected.add(CAMERA)
        fixture.coordinator.onDeviceDisappeared(CAMERA)

        fixture.coordinator.onDeviceDisconnected(CAMERA)

        assertEquals(0, fixture.shutdowns)
    }

    @Test
    fun `GATT reconnect during database lookup prevents shutdown`() = runBlocking {
        val fixture = Fixture()
        val resumeLookup = CompletableDeferred<Unit>()
        fixture.beforeAlwaysOnCheck = { resumeLookup.await() }
        val disappearance = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.onDeviceDisappeared(CAMERA)
        }

        fixture.connected.add(CAMERA)
        resumeLookup.complete(Unit)
        disappearance.join()

        assertEquals(0, fixture.shutdowns)
    }

    private class Fixture {
        val connected = mutableSetOf<String>()
        var alwaysOn = false
        var shutdowns = 0
        var beforeAlwaysOnCheck: (suspend () -> Unit)? = null
        val coordinator = ServicePresenceCoordinator(
            hasConnectedDevices = { connected.isNotEmpty() },
            hasAlwaysOnDevices = {
                beforeAlwaysOnCheck?.invoke()
                alwaysOn
            },
            onShutdownRequested = { shutdowns++ },
        )
    }

    private companion object {
        const val CAMERA = "AA:BB:CC:DD:EE:01"
        const val OTHER_CAMERA = "AA:BB:CC:DD:EE:02"
    }
}
