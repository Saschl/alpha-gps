package com.sasch.cameragps.sharednew.ui.devicelist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.add_camera
import cameragps.sharednew.generated.resources.device_list_need_help
import org.jetbrains.compose.resources.stringResource

/** iOS-style screen layout; hosts supply platform actions, notices and empty states. */
@Composable
fun DeviceListLayout(
    onNeedHelp: () -> Unit,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        header()
        // Measure the footer first so the list never hides it, even with larger text.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column {
                HorizontalDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = onNeedHelp) {
                        Text(stringResource(Res.string.device_list_need_help))
                    }
                }
            }
        }
    }
}

@Composable
fun AddCameraButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(Res.string.add_camera))
    }
}
