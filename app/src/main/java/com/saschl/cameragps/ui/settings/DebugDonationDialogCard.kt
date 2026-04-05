package com.saschl.cameragps.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.saschl.cameragps.utils.PreferencesManager

@Composable
internal fun DebugDonationDialogCard() {
    val context = LocalContext.current

    SettingsCard(title = "Debug: Donation Dialog") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    PreferencesManager.setForceDonationDialogOnNextAppStart(context, true)
                    Toast.makeText(
                        context,
                        "Donation dialog queued for next app start",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Text(text = "Show Donation Dialog On Next App Start")
            }

            Text(
                text = "Queues the donation popup for the next app launch only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

