package com.sasch.cameragps.sharednew.ui.simulator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SimulatorTimePacket(
    val time: String,
    val timezone: String,
    val dst: String,
    val problems: List<String>,
)

@Composable
fun SimulatorTimeCard(timeSync: SimulatorTimePacket?, location: SimulatorTimePacket?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last received time", style = MaterialTheme.typography.titleMedium)
            ReceivedTime("Direct time sync (CC13)", "Local time", timeSync)
            HorizontalDivider()
            ReceivedTime("GPS packet (DD11)", "UTC time", location)
            Text(
                "Received packet values, not a running camera clock. Timezone excludes DST. " +
                    "Values remain after disconnect and clear on a new connection or Stop.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReceivedTime(title: String, timeLabel: String, packet: SimulatorTimePacket?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (packet == null) {
            Text("No packet received")
        } else {
            Text("$timeLabel: ${packet.time}")
            Text("Timezone: ${packet.timezone}")
            Text("DST: ${packet.dst}")
            if (packet.problems.isNotEmpty()) {
                Text(packet.problems.joinToString("\n"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
