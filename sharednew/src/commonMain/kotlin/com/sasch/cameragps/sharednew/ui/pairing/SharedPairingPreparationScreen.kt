package com.sasch.cameragps.sharednew.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.back
import cameragps.sharednew.generated.resources.pairing_preparation_help
import cameragps.sharednew.generated.resources.pairing_preparation_note
import cameragps.sharednew.generated.resources.pairing_preparation_search
import cameragps.sharednew.generated.resources.pairing_preparation_searching
import cameragps.sharednew.generated.resources.pairing_preparation_step_bluetooth
import cameragps.sharednew.generated.resources.pairing_preparation_step_pairing
import cameragps.sharednew.generated.resources.pairing_preparation_step_ready
import cameragps.sharednew.generated.resources.pairing_preparation_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Platform-neutral instructions; the host opens its picker only after confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedPairingPreparationScreen(
    isSearching: Boolean,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onHelp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSearching) {
                        Icon(
                            painterResource(Res.drawable.arrow_back_24px),
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onSearch,
                        enabled = !isSearching,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(stringResource(
                                if (isSearching) Res.string.pairing_preparation_searching
                                else Res.string.pairing_preparation_search,
                            ))
                        }
                    }
                    TextButton(
                        onClick = onHelp,
                        enabled = !isSearching,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.pairing_preparation_help))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(Res.string.pairing_preparation_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            listOf(
                Res.string.pairing_preparation_step_bluetooth,
                Res.string.pairing_preparation_step_pairing,
                Res.string.pairing_preparation_step_ready,
            ).forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            "${index + 1}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        stringResource(step),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    stringResource(Res.string.pairing_preparation_note),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
