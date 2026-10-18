package com.sasch.cameragps.sharednew.remote.wifi

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidIpPrefixTest {
    @Test
    fun cameraSubnetIgnoresHostBitsInLocalAddress() {
        assertTrue(contains("192.168.122.57", 24, "192.168.122.1"))
        assertTrue(contains("192.168.122.57", 24, "192.168.122.0"))
        assertTrue(contains("192.168.122.57", 24, "192.168.122.255"))
        assertFalse(contains("192.168.122.57", 24, "192.168.123.1"))
        assertFalse(contains("192.168.122.57", 24, "10.168.122.1"))
    }

    @Test
    fun partialBytePrefixesRespectSubnetBoundaries() {
        assertTrue(contains("192.168.123.57", 23, "192.168.122.1"))
        assertFalse(contains("192.168.123.57", 23, "192.168.124.1"))
        assertTrue(contains("192.168.122.200", 25, "192.168.122.128"))
        assertTrue(contains("192.168.122.200", 25, "192.168.122.255"))
        assertFalse(contains("192.168.122.200", 25, "192.168.122.127"))
        assertTrue(contains("192.168.122.57", 31, "192.168.122.56"))
        assertFalse(contains("192.168.122.57", 31, "192.168.122.58"))
    }

    @Test
    fun zeroPrefixMatchesAnyAddressInSameFamily() {
        assertTrue(contains("192.168.122.57", 0, "10.0.0.1"))
        assertTrue(contains("2001:db8::1234", 0, "fe80::1"))
        assertFalse(contains("192.168.122.57", 0, "2001:db8::1"))
        assertFalse(contains("2001:db8::1234", 0, "192.168.122.1"))
    }

    @Test
    fun fullPrefixMatchesOnlySameAddress() {
        assertTrue(contains("192.168.122.57", 32, "192.168.122.57"))
        assertFalse(contains("192.168.122.57", 32, "192.168.122.56"))
        assertTrue(contains("2001:db8::1234", 128, "2001:db8::1234"))
        assertFalse(contains("2001:db8::1234", 128, "2001:db8::1235"))
    }

    @Test
    fun ipv6PrefixMatchesOnlyItsSubnet() {
        assertTrue(contains("2001:db8:1:2::1234", 64, "2001:db8:1:2::1"))
        assertFalse(contains("2001:db8:1:2::1234", 64, "2001:db8:1:3::1"))
        assertTrue(contains("2001:db8::ffff:1234:0:1", 65, "2001:db8::8000:0:0:1"))
        assertFalse(contains("2001:db8::ffff:1234:0:1", 65, "2001:db8::7fff:ffff:ffff:ffff"))
    }

    @Test
    fun differentAddressFamiliesNeverMatch() {
        assertFalse(contains("192.168.122.57", 24, "c0a8:7a39::1"))
        assertFalse(contains("c0a8:7a39::1", 24, "192.168.122.57"))
    }

    @Test
    fun invalidPrefixLengthsAreRejected() {
        for (prefixLength in listOf(-1, 33)) {
            assertFailsWith<IllegalArgumentException> {
                contains("192.168.122.57", prefixLength, "192.168.122.1")
            }
        }
        for (prefixLength in listOf(-1, 129)) {
            assertFailsWith<IllegalArgumentException> {
                contains("2001:db8::1234", prefixLength, "2001:db8::1")
            }
        }
    }

    private fun contains(prefixAddress: String, prefixLength: Int, address: String): Boolean =
        ipPrefixContains(
            InetAddress.getByName(prefixAddress),
            prefixLength,
            InetAddress.getByName(address)
        )
}
