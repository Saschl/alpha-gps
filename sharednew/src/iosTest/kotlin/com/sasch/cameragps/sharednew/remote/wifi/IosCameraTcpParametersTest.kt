@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import platform.Network.*
import kotlin.test.*

class IosCameraTcpParametersTest {
    @Test fun cameraConnectionsUsePlainTcpOnWifi() {
        val parameters = iosCameraTcpParameters()
        val stack = nw_parameters_copy_default_protocol_stack(parameters)
        var applicationProtocols = 0
        nw_protocol_stack_iterate_application_protocols(stack) { applicationProtocols++ }
        assertEquals(0, applicationProtocols, "Sony PTP/IP and live-view HTTP must not negotiate TLS")
        val transport = nw_protocol_stack_copy_transport_protocol(stack)
        assertNotNull(transport)
        assertTrue(nw_protocol_definition_is_equal(
            nw_protocol_options_copy_definition(transport), nw_protocol_copy_tcp_definition()))
        assertEquals(nw_interface_type_wifi, nw_parameters_get_required_interface_type(parameters))
    }
}
