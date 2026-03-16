package com.saschl.cameragps.ui

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.android_12_requires_keep_alive
import cameragps.sharednew.generated.resources.associated_devices
import cameragps.sharednew.generated.resources.no_devices_message
import cameragps.sharednew.generated.resources.no_devices_title
import cameragps.sharednew.generated.resources.not_paired_tap_to_pair_again
import com.saschl.cameragps.R
import com.saschl.cameragps.database.LogDatabase
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.LocationSenderService
import org.jetbrains.compose.resources.stringResource


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssociatedDevicesList(
    associatedDevices: List<AssociatedDeviceCompat>,
    onConnect: (AssociatedDeviceCompat) -> Unit,
) {
    val context = LocalContext.current
    val cameraDeviceDAO = LogDatabase.getDatabase(context.applicationContext).cameraDeviceDao()

    val enableServer = remember {
        LocationSenderService.activeTransmissions
    }
    Column {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stickyHeader {
                Text(
                    text = stringResource(Res.string.associated_devices),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (associatedDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                painterResource(R.drawable.baseline_photo_camera_24),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(Res.string.no_devices_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(Res.string.no_devices_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            items(associatedDevices, key = { device -> device.address }) { device ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 24.dp, end = 16.dp)
                        .clickable(
                            true,
                            onClick = {
                                onConnect(device)
                            }),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var isAlwaysOnEnabled by remember(device.address) { mutableStateOf(true) }

                    val isTransmissionRunning = enableServer[device.address]


                    LaunchedEffect(device.address) {
                        isAlwaysOnEnabled = cameraDeviceDAO.isDeviceAlwaysOnEnabled(device.address)
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.2f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center

                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painterResource(R.drawable.baseline_photo_camera_24),
                                contentDescription = "Device Icon"
                            )
                            TransmissionDot(
                                isRunning = isTransmissionRunning ?: false,
                            )
                        }


                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        Text(
                            fontWeight = FontWeight.Bold,
                            text = device.name
                        )

                        if (!device.isPaired) {
                            Text(
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                text = stringResource(Res.string.not_paired_tap_to_pair_again),
                            )
                        }

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                            && !isAlwaysOnEnabled
                        ) {
                            Text(
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                text = stringResource(Res.string.android_12_requires_keep_alive),
                            )
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.1f),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.keyboard_arrow_right_24px),
                            contentDescription = "Show details"
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 2.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun TransmissionDot(
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(12.dp), // fixed layout size so nothing moves
        contentAlignment = Alignment.Center
    ) {
        if (!isRunning) {  // Static red dot when disabled
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = Color.Red,
                        shape = CircleShape
                    )
            )
            return
        }

        val infiniteTransition = rememberInfiniteTransition(label = "txDot")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "txDotScale"
        )

        Box(
            modifier = modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    color = Color.Green,
                    shape = CircleShape
                )
        )
    }
}

