package com.sasch.cameragps.sharednew.diagnostics

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.logging.LogEntry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TroubleshootingReportTest {
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val uuid = "BB21A532-F48E-44EA-9BC0-EE2122389AE1"
    private val snapshot = TroubleshootingSnapshot(
        details = mapOf("App version" to "1.0 (123)", "OS" to "Android 16"),
        devices = listOf(
            CameraDevice(
                mac,
                deviceName = "My private camera",
                remoteControlEnabled = true
            )
        ),
        sessions = mapOf(
            mac to CameraSession(mac, phase = BleSessionPhase.Transmitting, pairingRetryCount = 2),
            uuid to CameraSession(uuid),
        ),
        tracking = true,
        transmitting = false,
        lastFixAgeMillis = null,
        capturedAt = 3_000,
    )

    @Test
    fun summaryIncludesUsefulStateAndLogsAreChronologicalWithConsistentCameraLabels() {
        val report = buildTroubleshootingReport(
            snapshot, listOf(
                entry(2, "Connected $uuid"),
                entry(1, "Handshake[$mac]: My private camera", exception = "Failed at $mac"),
            )
        )

        assertEquals(setOf("summary.txt", "logs.txt"), report.files.keys)
        assertContains(report.summary, "App version: 1.0 (123)")
        assertContains(report.summary, "Location tracking: true")
        assertContains(report.summary, "Location transmitting: false")
        assertContains(report.summary, "Last GPS fix age: unavailable")
        assertContains(report.summary, "BLE phase: Transmitting")
        assertContains(report.summary, "Pairing retries: 2")
        assertContains(report.summary, "Retained logs: 2")
        assertContains(
            report.summary,
            "Log range (UTC): 1970-01-01T00:00:00.001Z to 1970-01-01T00:00:00.002Z"
        )
        assertContains(report.logs, "Handshake[Camera 1]: Camera 1")
        assertContains(report.logs, "Failed at Camera 1")
        assertContains(report.logs, "Connected Camera 2")
        assertFalse(report.logs.indexOf("Connected") < report.logs.indexOf("Handshake"))
        for (text in report.files.values) {
            listOf(mac, uuid, "My private camera").forEach { assertFalse(it in text) }
        }
    }

    @Test
    fun redactsSensitiveMessagesTagsAndStackTracesButKeepsProtocolAndErrors() {
        val protocol = "0000dd11-0000-1000-8000-00805f9b34fb"
        val report = buildTroubleshootingReport(
            snapshot, listOf(
                entry(1, "latitude=52.123 longitude=13.456"),
                entry(2, "GPS packet: 0a 0b 0c"),
                entry(3, "Wi-Fi password=secret-value\nextra private payload"),
                entry(4, "Received 52.123, 13.456"),
                entry(
                    5,
                    "Read($protocol) failed status=133",
                    tag = mac,
                    exception = "SSID=private-network"
                ),
                entry(6, "Retry ${mac.lowercase().replace(':', '-')}"),
            )
        )

        listOf(
            "52.123",
            "13.456",
            "0a 0b 0c",
            "secret-value",
            "private payload",
            "private-network",
            mac
        )
            .forEach { assertFalse(it in report.logs) }
        assertContains(report.logs, "Camera 1: Read($protocol) failed status=133")
        assertContains(report.logs, "Retry Camera 1")
    }

    @Test
    fun unknownIdentifiersAreAlsoReplacedAndShortCameraNamesDoNotCorruptWords() {
        val other = "11:22:33:44:55:66"
        val state = snapshot.copy(devices = listOf(CameraDevice(mac, deviceName = "A")))
        val report = buildTroubleshootingReport(
            state,
            listOf(entry(1, "ATT failure for A at $other; retry $other"))
        )

        assertContains(report.logs, "ATT failure for Camera 1 at Device 3; retry Device 3")
        assertFalse(other in report.logs)
    }

    @Test
    fun emptyReportExplainsMissingLogsAndContainsNoInventedTimeRange() {
        val report = buildTroubleshootingReport(
            snapshot.copy(devices = emptyList(), sessions = emptyMap()),
            emptyList()
        )

        assertContains(report.summary, "Retained logs: 0")
        assertContains(report.summary, "Cameras: 0")
        assertFalse("Log range" in report.summary)
        assertEquals("No retained logs.\n", report.logs)
    }

    private fun entry(
        timestamp: Long,
        message: String,
        tag: String = "BLE",
        exception: String? = null
    ) =
        LogEntry(timestamp, timestamp, 5, tag, message, exception)
}
