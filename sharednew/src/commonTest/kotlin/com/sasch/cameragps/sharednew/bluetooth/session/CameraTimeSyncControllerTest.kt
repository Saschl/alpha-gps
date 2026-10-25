package com.sasch.cameragps.sharednew.bluetooth.session

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.Logger
import com.diamondedge.logging.PlatformLogger
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationQueue
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants as Sony

@OptIn(ExperimentalCoroutinesApi::class)
class CameraTimeSyncControllerTest {
    private val logger = RecordingLogger()

    @BeforeTest
    fun captureLogs() {
        KmLogging.setLoggers(logger)
    }

    @AfterTest
    fun restoreLogs() {
        KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true)))
    }

    @Test
    fun fallbackAndSpringForwardEncodeWallTimeAndDstFromTheSameOffsets() {
        fun packet(utc: String, dst: Int) =
            LocationPacketBuilder.buildTimeSyncPacket(Instant.parse(utc), 60, dst)
        assertContentEquals(
            byteArrayOf(12, 0, 0, 7, 0xEA.toByte(), 10, 25, 2, 59, 59, 1, 1, 0),
            packet("2026-10-25T00:59:59Z", 60),
        )
        assertContentEquals(
            byteArrayOf(12, 0, 0, 7, 0xEA.toByte(), 10, 25, 2, 0, 0, 0, 1, 0),
            packet("2026-10-25T01:00:00Z", 0),
        )
        assertEquals(1, packet("2026-03-29T00:59:59Z", 0)[7].toInt())
        assertEquals(3, packet("2026-03-29T01:00:00Z", 60)[7].toInt())
        val negative = LocationPacketBuilder.buildTimeSyncPacket(
            Instant.parse("2026-01-01T01:00:00Z"),
            -210,
            0
        )
        assertContentEquals(
            byteArrayOf(12, 0, 0, 7, 0xE9.toByte(), 12, 31, 21, 30, 0, 0, -3, 30),
            negative
        )
    }

    @Test
    fun capabilityParserDistinguishesUnsupportedFromUnknown() {
        assertEquals(true, parseTimeAreaSupport(byteArrayOf(6, 0, 2, 1, 0, 4, 1)))
        assertEquals(false, parseTimeAreaSupport(byteArrayOf(3, 0, 4, 0)))
        assertEquals(false, parseTimeAreaSupport(byteArrayOf(3, 0, 2, 1)))
        for (malformed in listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(3, 0, 4),
            byteArrayOf(4, 0, 4, 1),
            byteArrayOf(3, 0, 4, 2)
        )) {
            assertNull(parseTimeAreaSupport(malformed))
        }
    }

    @Test
    fun resultParserRejectsTruncatedUnrelatedAndMalformedNotifications() {
        assertEquals(CameraTimeSettingResult(true, 0u), CameraTimeSettingResult.parse(result()))
        assertEquals(
            CameraTimeSettingResult(false, 2u),
            CameraTimeSettingResult.parse(result(false, 2))
        )
        assertEquals(
            CameraTimeSettingResult(false, UInt.MAX_VALUE),
            CameraTimeSettingResult.parse(byteArrayOf(7, 0, 4, 0, -1, -1, -1, -1))
        )
        for (malformed in listOf(
            byteArrayOf(),
            result().copyOf(7),
            result() + 0,
            result().apply { this[0] = 6 },
            result().apply { this[2] = 3 },
            result().apply { this[3] = 2 })) {
            assertNull(CameraTimeSettingResult.parse(malformed))
        }
    }

    @Test
    fun subscribesBeforeWritingAndAcceptsResultBeforeWriteCallback() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.earlyResult = result()
        f.transport.holdWrite = true
        f.start("a")
        runCurrent()
        assertEquals(
            listOf(
                Sony.CAMERA_SUPPORT_UUID,
                Sony.INITIAL_SETTING_RESULT_UUID,
                Sony.TIME_SYNC_CHARACTERISTIC_UUID
            ),
            f.transport.operations.map { (_, op) ->
                when (op) {
                    is BleOperation.Read -> op.characteristicUuid
                    is BleOperation.Subscribe -> op.characteristicUuid
                    is BleOperation.Write -> op.characteristicUuid
                    else -> error("Unexpected operation")
                }
            },
        )
        assertTrue(f.completed.isEmpty())
        f.transport.completeWrite("A")
        runCurrent()
        assertEquals(listOf("A"), f.completed)
        assertTrue(logger.messages.any { "camera applied time/area" in it })
        advanceTimeBy(30_001)
        runCurrent()
        assertFalse(logger.messages.any { "result timed out" in it })
    }

    @Test
    fun rejectionIsReportedWithoutUndoingHandshakeCompletion() = runTest {
        val f = Fixture(backgroundScope)
        f.start("A")
        runCurrent()
        assertEquals(listOf("A"), f.completed)
        f.notify("A", result(false, 1))
        runCurrent()
        assertTrue(logger.messages.any { "camera rejected time/area (reason=1)" in it })
        assertTrue(f.registry.get("A")!!.isLocationReady)
    }

    @Test
    fun otherCamerasAndOtherResultTypesCannotConfirmThisAttempt() = runTest {
        val f = Fixture(backgroundScope)
        f.start("A")
        runCurrent()
        f.notify("B", result())
        f.notify("A", result().apply { this[2] = 3 })
        f.notify("A", byteArrayOf(7, 0, 4))
        runCurrent()
        assertFalse(logger.messages.any { "camera applied" in it })
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(logger.messages.any { "result timed out" in it })
        f.notify("A", result())
        runCurrent()
        assertFalse(logger.messages.any { "camera applied" in it })
    }

    @Test
    fun missingCapabilityAndResultCharacteristicsKeepLegacyTimeSettingWorking() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.characteristics.remove(Sony.CAMERA_SUPPORT_UUID)
        f.transport.characteristics.remove(Sony.INITIAL_SETTING_RESULT_UUID)
        f.start("A")
        runCurrent()
        assertEquals(1, f.transport.operations.size)
        assertEquals(listOf("A"), f.completed)
        assertTrue(logger.messages.any { "camera result unavailable" in it })
    }

    @Test
    fun unsupportedOrMissingDirectCommandStillCompletesAndDoesNotRefresh() = runTest {
        for (missingCommand in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            if (missingCommand) f.transport.characteristics.remove(Sony.TIME_SYNC_CHARACTERISTIC_UUID)
            else f.transport.support = byteArrayOf(3, 0, 4, 0)
            f.start("A")
            runCurrent()
            assertEquals(listOf("A"), f.completed)
            f.time = f.time.copy(dstOffsetMinutes = 0)
            advanceTimeBy(60_001)
            runCurrent()
            assertTrue(f.transport.writes().isEmpty())
            f.controller.clearAll()
        }
    }

    @Test
    fun unreadableCapabilityFallsBackToCharacteristicPresence() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.readStatus = BleOperationStatus.Failure
        f.start("A")
        runCurrent()
        assertEquals(1, f.transport.writes().size)
        assertEquals(listOf("A"), f.completed)
    }

    @Test
    fun subscriptionFailureDoesNotPreventWritingOrWaitForUnobservableResult() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.subscribeStatus = BleOperationStatus.AuthError
        f.start("A")
        runCurrent()
        assertEquals(1, f.transport.writes().size)
        assertEquals(listOf("A"), f.completed)
        assertTrue(logger.messages.any { "result subscription failed" in it })
        assertTrue(logger.messages.any { "camera result unavailable" in it })
    }

    @Test
    fun unchangedOffsetsDoNotResyncButFallbackAndZoneChangeDoWithoutGps() = runTest {
        val f = Fixture(backgroundScope)
        f.start("A")
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, f.transport.writes().size)

        f.time = CameraTime(Instant.parse("2026-10-25T01:00:00Z"), 60, 0)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, f.transport.writes().size)
        assertContentEquals(
            byteArrayOf(2, 0, 0, 0, 1, 0),
            f.transport.writes().last().value.copyOfRange(7, 13)
        )
        f.notify("A", result())
        runCurrent()

        f.time = f.time.copy(standardOffsetMinutes = -300)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, f.transport.writes().size)
        // Automatic refreshes must not re-complete the BLE handshake.
        assertEquals(listOf("A"), f.completed)
    }

    @Test
    fun changedDstSplitWithTheSameTotalOffsetStillResyncsEachCamera() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.earlyResult = result()
        f.start("A")
        f.start("B")
        runCurrent()
        assertEquals(2, f.transport.writes().size)
        f.time = f.time.copy(standardOffsetMinutes = 120, dstOffsetMinutes = 0)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(4, f.transport.writes().size)
        assertEquals(2, f.completed.size)
        for (packet in f.transport.writes().takeLast(2)) {
            assertEquals(0, packet.value[10].toInt())
            assertEquals(2, packet.value[11].toInt())
        }
    }

    @Test
    fun eitherCorrectionOptOutDefersRefreshUntilEnabled() = runTest {
        for (setting in CameraAutoCorrectionSetting.entries) {
            val f = Fixture(backgroundScope)
            f.start("A")
            runCurrent()
            f.registry.updateIfPresent("A") {
                when (setting) {
                    CameraAutoCorrectionSetting.Time -> it.copy(
                        autoTimeCorrection = CameraSettingState(
                            true,
                            false
                        )
                    )

                    CameraAutoCorrectionSetting.Area -> it.copy(
                        autoAreaAdjustment = CameraSettingState(
                            true,
                            false
                        )
                    )
                }
            }
            f.time = f.time.copy(dstOffsetMinutes = 0)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(1, f.transport.writes().size)
            f.registry.updateIfPresent("A") {
                it.copy(
                    autoTimeCorrection = CameraSettingState(
                        true,
                        true
                    ), autoAreaAdjustment = CameraSettingState(true, true)
                )
            }
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(2, f.transport.writes().size)
            f.controller.clearAll()
        }
    }

    @Test
    fun failedRefreshIsLoggedOncePerOffsetChangeWithoutRestartingHandshake() = runTest {
        val f = Fixture(backgroundScope)
        f.start("A")
        runCurrent()
        f.transport.writeStatus = BleOperationStatus.Failure
        f.time = f.time.copy(dstOffsetMinutes = 0)
        advanceTimeBy(90_001)
        runCurrent()
        assertEquals(2, f.transport.writes().size)
        assertEquals(listOf("A"), f.completed)
        assertTrue(logger.messages.any { "write failed" in it })
        assertTrue(f.registry.get("A")!!.isLocationReady)
    }

    @Test
    fun clearingAndReconnectCancelOldWaitersAndOffsetMonitors() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.holdWrite = true
        f.start("A")
        runCurrent()
        f.controller.clear("A")
        f.queue.cancelOperations("A", "disconnected")
        f.notify("A", result())
        f.transport.completeWrite("A")
        runCurrent()
        assertTrue(f.completed.isEmpty())
        f.transport.holdWrite = false
        f.start("A")
        runCurrent()
        assertEquals(listOf("A"), f.completed)
        f.controller.clearAll()
        f.time = f.time.copy(dstOffsetMinutes = 0)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(2, f.transport.writes().size)
        assertFalse(logger.messages.any { "camera applied" in it })
    }

    private class Fixture(scope: CoroutineScope) {
        val registry = CameraSessionRegistry()
        val transport = Transport()
        val queue = BleOperationQueue(transport, scope)
        val port = QueuedBleGattPort(queue, transport, registry)
        var time = CameraTime(Instant.parse("2026-10-25T00:59:59Z"), 60, 60)
        val controller = CameraTimeSyncController(port, registry, scope) { time }
        val completed = mutableListOf<String>()

        init {
            scope.launch {
                transport.events.collect { event ->
                    queue.onTransportEvent(event)
                    if (event is BleTransportEvent.CharacteristicChanged) controller.onResult(
                        event.identifier,
                        event.value
                    )
                }
            }
        }

        fun start(identifier: String) {
            val id = identifier.uppercase()
            registry.upsert(id) { it.copy(phase = BleSessionPhase.SyncingTime) }
            controller.synchronize(id) {
                completed += id
                registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Transmitting) }
                controller.onDeviceReady(id)
            }
        }

        fun notify(id: String, value: ByteArray) = transport.emit(
            BleTransportEvent.CharacteristicChanged(
                id,
                Sony.INITIAL_SETTING_RESULT_UUID,
                value
            )
        )
    }

    private class Transport : BlePeripheralTransport {
        private val channel = Channel<BleTransportEvent>(Channel.UNLIMITED)
        override val events = channel.receiveAsFlow()
        val characteristics = mutableSetOf(
            Sony.TIME_SYNC_CHARACTERISTIC_UUID,
            Sony.CAMERA_SUPPORT_UUID,
            Sony.INITIAL_SETTING_RESULT_UUID
        )
        val operations = mutableListOf<Pair<String, BleOperation>>()
        var support = byteArrayOf(3, 0, 4, 1)
        var readStatus = BleOperationStatus.Success
        var subscribeStatus = BleOperationStatus.Success
        var writeStatus = BleOperationStatus.Success
        var holdWrite = false
        var earlyResult: ByteArray? = null
        fun emit(event: BleTransportEvent) {
            channel.trySend(event)
        }

        fun writes() = operations.map { it.second }.filterIsInstance<BleOperation.Write>()
        fun completeWrite(id: String) = emit(
            BleTransportEvent.CharacteristicWritten(
                id,
                Sony.TIME_SYNC_CHARACTERISTIC_UUID,
                writeStatus
            )
        )

        override fun isConnected(identifier: String) = true
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) =
            characteristicUuid in characteristics

        override fun supportsWriteWithResponse(identifier: String, characteristicUuid: String) =
            hasCharacteristic(identifier, characteristicUuid)

        override fun initiateDiscoverServices(identifier: String) = false
        override fun initiateRead(identifier: String, characteristicUuid: String): Boolean {
            operations += identifier to BleOperation.Read(characteristicUuid)
            emit(
                BleTransportEvent.CharacteristicRead(
                    identifier,
                    characteristicUuid,
                    support,
                    readStatus
                )
            )
            return true
        }

        override fun initiateSubscribe(
            identifier: String,
            characteristicUuid: String,
            enable: Boolean
        ): Boolean {
            operations += identifier to BleOperation.Subscribe(characteristicUuid, enable)
            emit(
                BleTransportEvent.SubscriptionChanged(
                    identifier,
                    characteristicUuid,
                    enable,
                    subscribeStatus
                )
            )
            return true
        }

        override fun initiateWrite(
            identifier: String,
            characteristicUuid: String,
            value: ByteArray
        ): Boolean {
            operations += identifier to BleOperation.Write(characteristicUuid, value)
            earlyResult?.let {
                emit(
                    BleTransportEvent.CharacteristicChanged(
                        identifier,
                        Sony.INITIAL_SETTING_RESULT_UUID,
                        it
                    )
                )
            }
            if (!holdWrite) completeWrite(identifier)
            return true
        }
    }

    private class RecordingLogger : Logger {
        val messages = mutableListOf<String>()
        override fun verbose(tag: String, msg: String) = Unit
        override fun debug(tag: String, msg: String) = Unit
        override fun info(tag: String, msg: String) {
            messages += msg
        }

        override fun warn(tag: String, msg: String, t: Throwable?) {
            messages += msg
        }

        override fun error(tag: String, msg: String, t: Throwable?) {
            messages += msg
        }

        override fun isLoggingVerbose() = false
        override fun isLoggingDebug() = false
        override fun isLoggingInfo() = true
        override fun isLoggingWarning() = true
        override fun isLoggingError() = true
    }

    private companion object {
        fun result(applied: Boolean = true, reason: Byte = 0) =
            byteArrayOf(7, 0, 4, if (applied) 1 else 0, 0, 0, 0, reason)
    }
}
