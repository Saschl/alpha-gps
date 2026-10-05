package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SonyLiveViewEndpointTest {
    @Test
    fun readsNamespacedDescriptionAndRedactsUrl() {
        val dd = """<root><!-- <X_ScalarWebAPI_LiveView_URL>ignored</X_ScalarWebAPI_LiveView_URL> -->
            <s:X_ScalarWebAPI_LiveView_URL>http://192.168.1.1:8080/live?a=1&amp;b=2</s:X_ScalarWebAPI_LiveView_URL></root>"""
        val endpoint = SonyLiveViewEndpoint.fromDeviceDescription(dd.encodeToByteArray(), "192.168.1.1")
        assertEquals("http://192.168.1.1:8080/live?a=1&b=2", endpoint.url)
        assertFalse(endpoint.toString().contains("192.168"))
    }

    @Test
    fun rejectsOtherHostsCredentialsRedirectSchemesAndAmbiguousXml() {
        listOf("http://evil.example/live", "http://127.0.0.1@evil.example/live",
            "https://127.0.0.1/live", "http://127.0.0.1:99999/live", "http://127.0.0.1/live#fragment",
            "http://127.0.0.1/\r\nHeader").forEach {
            assertFailsWith<IllegalArgumentException> { SonyLiveViewEndpoint.fromUrl(it, "127.0.0.1") }
        }
        val element = "<X_ScalarWebAPI_LiveView_URL>http://127.0.0.1/live</X_ScalarWebAPI_LiveView_URL>"
        assertFailsWith<IllegalArgumentException> {
            SonyLiveViewEndpoint.fromDeviceDescription((element + element).encodeToByteArray(), "127.0.0.1")
        }
        assertFailsWith<IllegalArgumentException> {
            SonyLiveViewEndpoint.fromDeviceDescription(("<!DOCTYPE root>" + element).encodeToByteArray(), "127.0.0.1")
        }
    }
}
