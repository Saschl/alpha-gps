package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.*

class IosCameraGatewayTest {
    @Test fun choosesOnlyUnambiguousIpv4Gateway() {
        assertEquals("192.168.122.1", iosCameraGateway(listOf("fe80::1%en0", "192.168.122.1")))
        assertEquals("192.168.122.1", iosCameraGateway(listOf("192.168.122.1", "192.168.122.1")))
        assertNull(iosCameraGateway(listOf("192.168.0.1", "192.168.1.1")))
        assertNull(iosCameraGateway(emptyList()))
    }
    @Test fun ignoresInvalidAndNonUnicastAddresses() {
        for (address in listOf("fe80::1", "example.com", "127.0.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.168.000.1", "192.168.256.1")) {
            assertNull(iosCameraGateway(listOf(address)), address)
        }
    }
}
