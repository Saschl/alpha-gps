package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PtpIpCommandQueueTest {
    private class FakeTransport : PtpIpCommandTransport {
        val sent = mutableListOf<PtpIpPacket>()
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)

        override suspend fun send(packet: PtpIpPacket) {
            sent += packet
        }

        override suspend fun receive(): PtpIpPacket = replies.receive()
    }

    private fun response(id: Int, code: Int = 0x2001): PtpIpPacket =
        PtpIpPacket(7, byteArrayOf(code.toByte(), (code ushr 8).toByte(), id.toByte(), 0, 0, 0))

    private fun dataPacket(type: Int, id: Int, bytes: ByteArray): PtpIpPacket =
        PtpIpPacket(type, byteArrayOf(id.toByte(), 0, 0, 0) + bytes)

    @Test
    fun serializesCommandsAndIgnoresLateResponseWithoutRetryingCapture() = runTest {
        val transport = FakeTransport()
        val queue = PtpIpCommandQueue(transport, backgroundScope, timeoutMs = 100)
        val first = async { queue.executeNoData(0xD2C6) }
        val second = async { queue.executeNoData(0xD2C6) }
        runCurrent()
        assertEquals(1, transport.sent.size)

        advanceTimeBy(100)
        runCurrent()
        assertEquals(PtpIpTransactionResult.Uncertain, first.await())
        assertEquals(2, transport.sent.size)
        transport.replies.send(response(1)) // timed-out capture response
        runCurrent()
        assertTrue(!second.isCompleted)
        transport.replies.send(response(2))
        runCurrent()
        assertEquals(0x2001, assertIs<PtpIpTransactionResult.Response>(second.await()).response.code)
        assertEquals(2, transport.sent.size) // two deliberate calls, no automatic repeat
        queue.close()
    }

    @Test
    fun closingConnectionReleasesActiveAndParkedCallers() = runTest {
        val transport = FakeTransport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val first = async { queue.executeNoData(0x1002) }
        val second = async { queue.executeNoData(0x1002) }
        runCurrent()
        queue.close()
        runCurrent()
        assertEquals(PtpIpTransactionResult.Closed, first.await())
        assertEquals(PtpIpTransactionResult.Closed, second.await())
    }

    @Test
    fun rejectsResponseFromFutureTransaction() = runTest {
        val transport = FakeTransport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val call = async { queue.executeNoData(0x1002) }
        runCurrent()
        transport.replies.send(response(2))
        runCurrent()
        assertIs<PtpIpTransactionResult.Failure>(call.await())
        queue.close()
    }

    @Test
    fun assemblesBoundedDataInBeforeResponse() = runTest {
        val transport = FakeTransport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val call = async { queue.executeDataIn(0x1001) }
        runCurrent()
        transport.replies.send(dataPacket(9, 1, byteArrayOf(5, 0, 0, 0, 0, 0, 0, 0)))
        transport.replies.send(dataPacket(10, 1, byteArrayOf(1, 2)))
        transport.replies.send(dataPacket(12, 1, byteArrayOf(3, 4, 5)))
        transport.replies.send(response(1))
        runCurrent()
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5),
            assertIs<PtpIpTransactionResult.Response>(call.await()).data)
        queue.close()
    }

    @Test
    fun rejectsOversizedDataBeforeAllocation() = runTest {
        val transport = FakeTransport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val call = async { queue.executeDataIn(0x1001) }
        runCurrent()
        transport.replies.send(dataPacket(9, 1, byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0)))
        runCurrent()
        assertIs<PtpIpTransactionResult.Failure>(call.await())
        queue.close()
    }

    @Test
    fun capabilityReaderParsesOnlyAdvertisedOperationCodes() = runTest {
        val transport = FakeTransport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val result = async { PtpIpCapabilityReader(queue).read() }
        runCurrent()
        val request = transport.sent.single().body
        assertEquals(SonyPtpOperation.GET_DEVICE_INFO,
            (request[4].toInt() and 0xff) or ((request[5].toInt() and 0xff) shl 8))
        val dataset = byteArrayOf(100, 0, 6, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0,
            2, 0, 0, 0, 1, 16, 1, 0x92.toByte())
        transport.replies.send(dataPacket(9, 1, byteArrayOf(dataset.size.toByte(), 0, 0, 0, 0, 0, 0, 0)))
        transport.replies.send(dataPacket(12, 1, dataset))
        transport.replies.send(response(1))
        runCurrent()
        val info = assertIs<PtpIpCapabilityResult.Available>(result.await()).deviceInfo
        assertTrue(info.supports(SonyPtpOperation.SDIO_CONNECT))
        assertTrue(!info.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE))
        queue.close()
    }

    @Test
    fun dataOutUsesOneTransactionAndExactSonyEnvelope() = runTest {
        val transport = FakeTransport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val capture = async { queue.executeDataOut(0x9207, listOf(0xd2c2, 1),
            byteArrayOf(2, 0)) }
        runCurrent()
        assertEquals(listOf(6, 9, 12), transport.sent.map { it.type })
        assertContentEquals(byteArrayOf(2, 0, 0, 0), transport.sent[0].body.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(2, 0, 0, 0), transport.sent[1].body.copyOfRange(4, 8))
        assertContentEquals(byteArrayOf(2, 0), transport.sent[2].body.copyOfRange(4, 6))
        transport.replies.send(response(1))
        runCurrent()
        assertEquals(0x2001, assertIs<PtpIpTransactionResult.Response>(capture.await()).response.code)
        queue.close()
    }
}
