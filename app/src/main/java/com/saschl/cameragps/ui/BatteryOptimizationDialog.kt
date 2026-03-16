package com.saschl.cameragps.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.battery_optimization_cancel
import cameragps.sharednew.generated.resources.battery_optimization_dont_show
import cameragps.sharednew.generated.resources.battery_optimization_message
import cameragps.sharednew.generated.resources.battery_optimization_proceed
import cameragps.sharednew.generated.resources.battery_optimization_title
import cameragps.sharednew.generated.resources.battery_optimization_xiaomi_autostart
import com.saschl.cameragps.utils.BatteryOptimizationUtil
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@Composable
fun BatteryOptimizationDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.battery_optimization_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.battery_optimization_message),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = {
                        try {
                            val uri = "package:${context.packageName}".toUri()
                            val intent =
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    uri
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            context.startActivity(intent)
                            Timber.i("Opened battery optimization settings for package: ${context.packageName}")
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to open battery optimization settings, trying fallback")
                            // Fallback to general settings if specific intent fails
                            try {
                                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(fallbackIntent)
                                Timber.i("Opened general battery optimization settings")
                            } catch (fallbackException: Exception) {
                                Timber.e(fallbackException, "Failed to open any battery optimization settings")
                            }
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(Res.string.battery_optimization_proceed),
                        fontWeight = FontWeight.Medium
                    )
                }
                TextButton(
                    onClick = {
                        try {

                            context.startActivity(
                                BatteryOptimizationUtil.getResolveableComponentName(
                                    context
                                )
                            )
                            Timber.i("Opened autostart settings")
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "Failed to openbattery_optimization_xiaomi_autostart autostart settings, trying fallback"
                            )
                            try {
                                // Fallback to general app settings
                                val fallbackIntent =
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                context.startActivity(fallbackIntent)
                                Timber.i("Opened app details settings as fallback")
                            } catch (fallbackException: Exception) {
                                Timber.e(fallbackException, "Failed to open any settings")
                            }
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(Res.string.battery_optimization_xiaomi_autostart),
                        fontWeight = FontWeight.Medium
                    )
                }

                TextButton(
                    onClick = {
                        PreferencesManager.setBatteryOptimizationDialogDismissed(context, true)
                        Timber.i("Battery optimization dialog dismissed permanently")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.battery_optimization_dont_show))
                }
                
                TextButton(
                    onClick = {
                        Timber.i("Battery optimization dialog cancelled")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.battery_optimization_cancel))
                }
            }
        }
    )
}

fun isXiaomiDevice(): Boolean {
    return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) || Build.MANUFACTURER.equals("Redmi", ignoreCase = true)
}

fun isOppoDevice(): Boolean {
    return Build.MANUFACTURER.equals(
        "Oppo",
        ignoreCase = true
    ) || Build.MANUFACTURER.equals(
        "Realme",
        ignoreCase = true
    ) || Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
}

