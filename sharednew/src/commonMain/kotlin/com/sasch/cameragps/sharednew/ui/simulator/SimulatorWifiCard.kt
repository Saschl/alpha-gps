package com.sasch.cameragps.sharednew.ui.simulator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SimulatorWifiState(
    val enabled: Boolean = false,
    val starting: Boolean = false,
    val active: Boolean = false,
    val ssid: String = "",
    val password: String = "",
    val status: String = "Enable Wi-Fi simulation before connecting from Alpha GPS.",
    val captures: Int = 0,
    val legacy: Boolean = true,
    val confirmCapture: Boolean = true,
) {
    override fun toString() = "SimulatorWifiState(enabled=$enabled, starting=$starting, active=$active)"
}

/** Shared UI for the debug camera peripheral. */
@Composable
fun SimulatorWifiCard(
    state: SimulatorWifiState,
    onEnable: (Boolean) -> Unit,
    onToggleHotspot: () -> Unit,
    onLegacy: (Boolean) -> Unit,
    onConfirmCapture: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wi-Fi remote shooting", style = MaterialTheme.typography.titleMedium)
            WifiSwitch("Enable Wi-Fi simulation", state.enabled, onChange = onEnable)
            WifiSwitch("Legacy session (ZV-1-style)", state.legacy,
                enabled = !state.active && !state.starting, onChange = onLegacy)
            WifiSwitch("Confirm Wi-Fi captures", state.confirmCapture, onChange = onConfirmCapture)
            Text(state.status)
            if (state.active) {
                Text("Network: ${state.ssid}\nPassword: ${state.password}")
                Text("${state.captures} simulated photos")
            }
            OutlinedButton(onClick = onToggleHotspot, enabled = state.enabled) {
                Text(if (state.active || state.starting) "Stop hotspot" else "Start hotspot")
            }
            Text(
                "Automatic: start the BLE simulator, connect Alpha GPS, then use its automatic Wi-Fi setup.\n" +
                    "Manual: start this hotspot, join it in the other phone’s Wi-Fi settings, then " +
                    "use manual connection in Android Alpha GPS with the IP blank. Stay connected if Android warns there is no internet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WifiSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
