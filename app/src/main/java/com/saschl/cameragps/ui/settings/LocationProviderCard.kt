package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saschl.cameragps.R
import com.saschl.cameragps.shared.ui.settings.LocationProvider
import com.saschl.cameragps.utils.PreferencesManager

@Composable
internal fun LocationProviderCard(
    locationProvider: LocationProvider,
    onProviderChange: (LocationProvider) -> Unit
) {
    SettingsCard(title = stringResource(R.string.location_provider_title)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onProviderChange(LocationProvider.PLAY_SERVICES)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = locationProvider == LocationProvider.PLAY_SERVICES,
                    onClick = {
                        onProviderChange(LocationProvider.PLAY_SERVICES)
                    }
                )
                Text(
                    text = stringResource(R.string.location_provider_play_services),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onProviderChange(LocationProvider.PLATFORM)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = locationProvider == LocationProvider.PLATFORM,
                    onClick = {
                        onProviderChange(LocationProvider.PLATFORM)
                    }
                )
                Text(
                    text = stringResource(R.string.location_provider_platform),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Text(
            text = stringResource(R.string.location_provider_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.location_provider_restart_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

