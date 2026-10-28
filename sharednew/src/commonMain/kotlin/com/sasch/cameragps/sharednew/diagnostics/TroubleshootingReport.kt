package com.sasch.cameragps.sharednew.diagnostics

import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.logging.LogEntry
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

data class TroubleshootingSnapshot(
    val details: Map<String, String>,
    val devices: List<CameraDevice>,
    val sessions: Map<String, CameraSession>,
    val tracking: Boolean,
    val transmitting: Boolean,
    val lastFixAgeMillis: Long?,
    val capturedAt: Long = Clock.System.now().toEpochMilliseconds(),
)

data class TroubleshootingReport(val summary: String, val logs: String) {
    val files: Map<String, String> get() = mapOf("summary.txt" to summary, "logs.txt" to logs)
}

interface TroubleshootingSharing {
    suspend fun share(report: TroubleshootingReport)
    fun copySummary(summary: String)
}

class TroubleshootingReporter(
    private val repository: LogRepository,
    private val snapshot: suspend () -> TroubleshootingSnapshot,
    private val sharing: TroubleshootingSharing,
) {
    private suspend fun create(): TroubleshootingReport {
        val state = snapshot()
        val logs = repository.getLogsForExport()
        return withContext(Dispatchers.Default) { buildTroubleshootingReport(state, logs) }
    }

    suspend fun share() = sharing.share(create())

    suspend fun copySummary() = sharing.copySummary(create().summary)
}

internal fun buildTroubleshootingReport(
    snapshot: TroubleshootingSnapshot,
    entries: List<LogEntry>,
): TroubleshootingReport {
    val identifiers = (snapshot.devices.map { it.mac } + snapshot.sessions.keys)
        .map { it.uppercase() }.distinct().sorted()
    val labels = identifiers.mapIndexed { index, id -> id to "Camera ${index + 1}" }.toMap()
    val redactor = ReportRedactor(labels, snapshot.devices)
    val ordered = entries.sortedWith(compareBy({ it.timestamp }, { it.id }))
    val summary = buildString {
        appendLine("Alpha GPS troubleshooting report")
        appendLine("Generated: ${Instant.fromEpochMilliseconds(snapshot.capturedAt)}")
        snapshot.details.forEach { (key, value) -> appendLine("$key: ${redactor.redact(value)}") }
        appendLine("Location tracking: ${snapshot.tracking}")
        appendLine("Location transmitting: ${snapshot.transmitting}")
        appendLine("Last GPS fix age: ${snapshot.lastFixAgeMillis?.let { "$it ms" } ?: "unavailable"}")
        appendLine("Retained logs: ${ordered.size}")
        if (ordered.isNotEmpty()) {
            appendLine(
                "Log range (UTC): ${Instant.fromEpochMilliseconds(ordered.first().timestamp)} to " +
                        Instant.fromEpochMilliseconds(ordered.last().timestamp)
            )
        }
        appendLine("Log retention: up to 5000 entries; trimmed to 4000 when exceeded.")
        appendLine("Camera identifiers and known camera names are replaced; sensitive location/credential messages are filtered.")
        appendLine()
        appendLine("Cameras: ${identifiers.size}")
        identifiers.forEach { id ->
            val device = snapshot.devices.firstOrNull { it.mac.equals(id, ignoreCase = true) }
            val session =
                snapshot.sessions.values.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
            appendLine(labels.getValue(id))
            if (device != null) {
                appendLine("  Enabled: ${device.deviceEnabled}; always on: ${device.alwaysOnEnabled}; remote enabled: ${device.remoteControlEnabled}")
            }
            appendLine("  BLE phase: ${session?.phase ?: "Disconnected"}")
            if (session != null) {
                appendLine("  Remote active: ${session.remoteFeatureActive}; shutter sequence: ${session.shutterSequenceActive}")
                appendLine("  Pairing retries: ${session.pairingRetryCount}; config read retried: ${session.hasRetriedConfigRead}")
                appendLine("  Location disabled by camera: ${session.locationDisabledByCamera}")
                appendLine("  Wi-Fi phase: ${session.wifiRemote.phase}")
            }
        }
    }
    val logs = if (ordered.isEmpty()) "No retained logs.\n" else ordered.joinToString(
        "\n\n",
        postfix = "\n"
    ) {
        val priority = when (it.priority) {
            2 -> "V"
            3 -> "D"
            4 -> "I"
            5 -> "W"
            6 -> "E"
            else -> it.priority.toString()
        }
        "[${Instant.fromEpochMilliseconds(it.timestamp)}] [$priority] " +
                "${redactor.redact(it.tag ?: "App")}: ${redactor.redact(it.message)}" +
                (it.exception?.let { exception -> "\n${redactor.redact(exception)}" } ?: "")
    }
    return TroubleshootingReport(summary, logs)
}
