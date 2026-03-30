package com.saschl.cameragps.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.saschl.cameragps.R
import com.saschl.cameragps.utils.PreferencesManager



/**
 * Enhanced PermissionBox that properly handles background location permission according to Android guidelines.
 * For Android 10+ (API 29+), background location must be requested separately from foreground location.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun EnhancedLocationPermissionBox(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    onAllPermissionsGranted: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    var errorText by remember { mutableStateOf("") }
    var ignorePermissions by remember {
        mutableStateOf(PreferencesManager.isPermissionsIgnored(context))
    }

    // Foreground location permissions
    val foregroundLocationPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH)
    }

    val foregroundPermissionState = rememberMultiplePermissionsState(
        permissions = foregroundLocationPermissions
    ) { map ->
        val rejectedPermissions = map.filterValues { !it }.keys
        errorText = if (rejectedPermissions.isNotEmpty()) {
            context.getString(
                R.string.permissions_required_app_error,
                rejectedPermissions.joinToString()
            )
        } else {
            ""
        }
    }

    // Background location permission (separate for Android 10+)
    val backgroundLocationPermission = rememberPermissionState(
        permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) { granted ->
        errorText = if (!granted) {
            context.getString(R.string.background_location_required_error)
        } else {
            ""
        }
    }

    val allForegroundGranted = foregroundPermissionState.allPermissionsGranted
    val backgroundGranted = backgroundLocationPermission.status.isGranted
    val allPermissionsGranted = allForegroundGranted && backgroundGranted
    val shouldAllowContent = allPermissionsGranted || ignorePermissions

    Box(
            modifier = Modifier
                .fillMaxSize()
                .then(modifier),
        contentAlignment = if (shouldAllowContent) {
                contentAlignment
            } else {
                Alignment.Center
            },
        ) {
        if (shouldAllowContent) {
                onAllPermissionsGranted()
            } else {
                EnhancedPermissionScreen(
                    foregroundPermissionState = foregroundPermissionState,
                    backgroundLocationPermission = backgroundLocationPermission,
                    allForegroundGranted = allForegroundGranted,
                    errorText = errorText,
                    onContinueAnyway = {
                        ignorePermissions = true
                        PreferencesManager.setPermissionsIgnored(context, true)
                    },
                )
            }
        }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun EnhancedPermissionScreen(
    foregroundPermissionState: MultiplePermissionsState,
    backgroundLocationPermission: PermissionState,
    allForegroundGranted: Boolean,
    errorText: String,
    onContinueAnyway: () -> Unit,
) {
    val context = LocalContext.current
    var showForegroundRationale by remember { mutableStateOf(false) }
    var showBackgroundRationale by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_gps_permissions),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
            color = MaterialTheme.colorScheme.onSurface
        )

        // Step 1: Foreground Location Permissions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (allForegroundGranted)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.step1_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.step1_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (!allForegroundGranted) {
                    val revokedPermissions =
                        foregroundPermissionState.revokedPermissions.map { it ->
                            stringResource(getPermissionDescription(it.permission))
                        }
                            .joinToString("\n") { " - $it" }

                    Text(
                        text = stringResource(R.string.step1_missing) + "\n" + revokedPermissions,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = {
                            if (foregroundPermissionState.shouldShowRationale) {
                                showForegroundRationale = true
                            } else {
                                foregroundPermissionState.launchMultiplePermissionRequest()
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.step1_grant))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.step1_granted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Step 2: Background Location Permission (only show if foreground is granted)
        if (allForegroundGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (backgroundLocationPermission.status.isGranted)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.step2_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.step2_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!backgroundLocationPermission.status.isGranted) {
                            Text(
                                text = stringResource(R.string.step2_android10_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                        Button(
                            onClick = {
                                if (backgroundLocationPermission.status.shouldShowRationale) {
                                    showBackgroundRationale = true
                                } else {
                                    backgroundLocationPermission.launchPermissionRequest()
                                }
                            },
                        ) {
                            Text(text = stringResource(R.string.step2_grant))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.step2_granted),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (errorText.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(text = stringResource(R.string.app_settings))
            }
            TextButton(onClick = onContinueAnyway) {
                Text(text = stringResource(R.string.continue_anyway))
            }
        }
    }

    // Rationale dialogs
    if (showForegroundRationale) {
        AlertDialog(
            onDismissRequest = { showForegroundRationale = false },
            title = { Text(stringResource(R.string.location_bluetooth_access_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.location_bluetooth_access_message))
                    Text(text = stringResource(R.string.location_bluetooth_benefit_1))
                    Text(text = stringResource(R.string.location_bluetooth_benefit_2))
                    Text(text = stringResource(R.string.location_bluetooth_benefit_3))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForegroundRationale = false
                        foregroundPermissionState.launchMultiplePermissionRequest()
                    }
                ) {
                    Text(stringResource(R.string.grant_permissions_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForegroundRationale = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    if (showBackgroundRationale) {
        AlertDialog(
            onDismissRequest = { showBackgroundRationale = false },
            title = { Text(stringResource(R.string.background_location_access_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.background_location_access_message))
                    Text(text = stringResource(R.string.background_location_benefit_1))
                    Text(text = stringResource(R.string.background_location_benefit_2))
                    Text(text = stringResource(R.string.background_location_benefit_3))
                    Text(fontWeight = FontWeight.Bold , text = stringResource(R.string.background_location_instruction))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundRationale = false
                        backgroundLocationPermission.launchPermissionRequest()
                    }
                ) {
                    Text(stringResource(R.string.continue_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundRationale = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

}

/**
 * Gates [content] behind the BLUETOOTH_CONNECT runtime permission (API 31+).
 * On older API levels the content is shown directly.
 * Shows a permission-request UI when the permission is missing, with an optional
 * "continue anyway" escape hatch (consistent with [EnhancedLocationPermissionBox]).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BluetoothConnectPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current

    var ignorePermissions by remember {
        mutableStateOf(PreferencesManager.isPermissionsIgnored(context))
    }

    // Always call rememberPermissionState unconditionally (Compose rule).
    // On API < 31 the permission is auto-granted, so isGranted == true there.
    val bluetoothConnectPermission = rememberPermissionState(
        permission = Manifest.permission.BLUETOOTH_CONNECT
    )

    val isGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            || bluetoothConnectPermission.status.isGranted

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentAlignment = if (isGranted || ignorePermissions) Alignment.TopStart else Alignment.Center,
    ) {
        if (isGranted || ignorePermissions) {
            content()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.bluetooth_connect_required_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.bluetooth_connect_required_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
                Button(
                    onClick = { bluetoothConnectPermission.launchPermissionRequest() },
                ) {
                    Text(text = stringResource(R.string.bluetooth_connect_grant_button))
                }
                // Permanently denied – show explanation and offer opening app settings
                if (!bluetoothConnectPermission.status.shouldShowRationale) {
                    Text(
                        text = stringResource(R.string.bluetooth_connect_permanently_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    TextButton(
                        onClick = {
                            val intent =
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.app_settings))
                    }
                }

            }
        }
    }
}

/**
 * Gets a user-friendly description for what the permission is used for
 */
fun getPermissionDescription(permission: String): Int {
    return when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> R.string.permission_gps
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> R.string.permission_background_gps
        Manifest.permission.BLUETOOTH_CONNECT -> R.string.permission_bluetooth
        Manifest.permission.POST_NOTIFICATIONS -> R.string.permission_notifications
        else -> -1
    }
}
