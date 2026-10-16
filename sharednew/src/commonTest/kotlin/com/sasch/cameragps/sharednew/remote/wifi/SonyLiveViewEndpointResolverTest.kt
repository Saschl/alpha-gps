package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SonyLiveViewEndpointResolverTest : WifiLoggingTest() {
    private val host = "192.168.1.1"
    private val url = "http://$host:8080/live?session=private"

    private fun number(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }
    private fun string(value: String): ByteArray = byteArrayOf((value.length + 1).toByte()) +
        value.fold(byteArrayOf()) { bytes, char -> bytes + number(char.code.toLong(), 2) } + number(0, 2)

    private fun property(
        value: String = url,
        enabled: Int = 1,
        code: Int = 0xd278,
        current: ByteArray = string(value),
    ) = number(code.toLong(), 2) + number(0xffff, 2) + byteArrayOf(0, enabled.toByte()) +
        byteArrayOf(0) + current + byteArrayOf(0)

    private fun dataset(vararg records: ByteArray) =
        number(records.size.toLong(), 8) + records.fold(byteArrayOf()) { a, b -> a + b }

    private fun capabilities(dd: String? = null, vendorVersion: Long? = null) = SonyPtpInitializationResult.Ready(
        null, vendorVersion, SonyExtendedDeviceInfo(300, setOf(0xd278), emptySet()),
        PtpDeviceInfo(setOf(0x9209)), dd?.encodeToByteArray(),
    )

    private fun description(value: String = url) =
        "<root><X_ScalarWebAPI_LiveView_URL>$value</X_ScalarWebAPI_LiveView_URL></root>"

    private inner class Transport(
        var data: ByteArray = dataset(property()),
        val responseCode: Int = 0x2001,
        val dropResponse: Boolean = false,
    ) : PtpIpCommandTransport {
        val requests = mutableListOf<PtpIpPacket>()
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        override suspend fun receive() = replies.receive()
        override suspend fun send(packet: PtpIpPacket) {
            requests += packet
            check(packet.type == PtpIpPacketType.OPERATION_REQUEST)
            assertContentEquals(number(0x9209, 2), packet.body.copyOfRange(4, 6))
            if (dropResponse) return
            val id = packet.body[6].toInt() and 255
            if (responseCode == 0x2001) {
                replies.trySend(PtpIpOperations.startData(id, data.size))
                replies.trySend(PtpIpOperations.endData(id, data))
            }
            replies.trySend(PtpIpPacket(7, number(responseCode.toLong(), 2) + number(id.toLong(), 4)))
        }
    }

    @Test
    fun prefersDescriptionWithoutReadingProperties() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val ready = capabilities(description()).copy(deviceInfo = PtpDeviceInfo(emptySet()))
        assertEquals(url, SonyLiveViewEndpointResolver(queue, ready).resolve(host).url)
        assertTrue(transport.requests.isEmpty())
        queue.close()
    }

    @Test
    fun missingOrEmptyDescriptionUrlUsesEnabledOrDisplayOnlyProperty() = runTest {
        for (dd in listOf(null, "<root/>", description(""))) {
            for (enabled in listOf(1, 2)) {
                val transport = Transport(dataset(property(enabled = enabled)))
                val queue = PtpIpCommandQueue(transport, backgroundScope)
                val endpoint = SonyLiveViewEndpointResolver(queue, capabilities(dd)).resolve(host)
                assertEquals(url, endpoint.url)
                assertFalse(endpoint.toString().contains(host))
                assertEquals(1, transport.requests.size)
                assertContentEquals(number(0, 4), transport.requests.single().body.copyOfRange(10, 14))
                queue.close()
            }
        }
    }

    @Test
    fun modernPropertyReadIncludesVendorOption() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        assertEquals(url, SonyLiveViewEndpointResolver(queue, capabilities(vendorVersion = 310)).resolve(host).url)
        assertContentEquals(number(0, 4) + number(1, 4), transport.requests.single().body.copyOfRange(10, 18))
        queue.close()
    }

    @Test
    fun unavailableOrWrongTypePropertyDoesNotResolve() = runTest {
        val numeric = number(0xd278, 2) + number(4, 2) + byteArrayOf(0, 1) + number(0, 2) + number(1, 2) + byteArrayOf(0)
        for (data in listOf(dataset(), dataset(property(enabled = 0)), dataset(property("")), dataset(numeric))) {
            val queue = PtpIpCommandQueue(Transport(data), backgroundScope)
            assertFailsWith<IllegalStateException> {
                SonyLiveViewEndpointResolver(queue, capabilities()).resolve(host)
            }
            queue.close()
        }
    }

    @Test
    fun missingCapabilitiesDoNotSendPropertyRead() = runTest {
        for (ready in listOf(
            capabilities().copy(deviceInfo = PtpDeviceInfo(emptySet())),
            capabilities().copy(extendedInfo = SonyExtendedDeviceInfo(300, emptySet(), emptySet())),
        )) {
            val transport = Transport()
            val queue = PtpIpCommandQueue(transport, backgroundScope)
            assertFailsWith<IllegalStateException> { SonyLiveViewEndpointResolver(queue, ready).resolve(host) }
            assertTrue(transport.requests.isEmpty())
            queue.close()
        }
    }

    @Test
    fun rejectedOrTimedOutPropertyReadIsNotRetried() = runTest {
        for (transport in listOf(Transport(responseCode = 0x2005), Transport(dropResponse = true))) {
            val queue = PtpIpCommandQueue(transport, backgroundScope)
            assertFailsWith<IllegalStateException> {
                SonyLiveViewEndpointResolver(queue, capabilities()).resolve(host)
            }
            assertEquals(1, transport.requests.size)
            queue.close()
        }
    }

    @Test
    fun propertyUrlsMustTargetCameraPeer() = runTest {
        for (value in listOf("http://other.example/live", "https://$host/live", "http://$host@other.example/live")) {
            val queue = PtpIpCommandQueue(Transport(dataset(property(value))), backgroundScope)
            assertFailsWith<IllegalArgumentException> {
                SonyLiveViewEndpointResolver(queue, capabilities()).resolve(host)
            }
            queue.close()
        }
    }

    @Test
    fun invalidOrAmbiguousDescriptionDoesNotTriggerPropertyFallback() = runTest {
        for (dd in listOf(description("http://other.example/live"), description() + description(), "<!DOCTYPE root><root/>")) {
            val transport = Transport()
            val queue = PtpIpCommandQueue(transport, backgroundScope)
            assertFailsWith<IllegalArgumentException> { SonyLiveViewEndpointResolver(queue, capabilities(dd)).resolve(host) }
            assertTrue(transport.requests.isEmpty())
            queue.close()
        }
    }

    @Test
    fun selectedStringIsBoundedAndRedactedWhileOtherStringsRemainOpaque() {
        val opaque = property(code = 0x5000, current = byteArrayOf(1, 42, 42))
        val properties = SonyCameraProperties.parse(dataset(opaque, property()), emptySet(), emptySet(), setOf(0xd278))
        assertEquals(url, properties[0xd278]?.stringValue)
        assertFalse(properties.toString().contains("private"))
        for (data in listOf(
            dataset(property(), property()),
            dataset(property()).dropLast(2).toByteArray(),
            dataset(property(current = byteArrayOf(1, 42, 42))),
            dataset(property(current = string("a\u0000b"))),
        )) {
            assertFailsWith<IllegalArgumentException> {
                SonyCameraProperties.parse(data, emptySet(), emptySet(), setOf(0xd278))
            }
        }
    }
}
