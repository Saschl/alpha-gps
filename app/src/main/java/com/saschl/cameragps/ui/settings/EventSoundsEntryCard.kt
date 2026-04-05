package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.event_sounds_open_section
import cameragps.sharednew.generated.resources.event_sounds_open_section_description
import cameragps.sharednew.generated.resources.event_sounds_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EventSoundsEntryCard(onOpenClick: () -> Unit) {
    SettingsCard(title = stringResource(Res.string.event_sounds_title)) {
        Text(
            text = stringResource(Res.string.event_sounds_open_section_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onOpenClick) {
                Text(
                    text = stringResource(Res.string.event_sounds_open_section),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

