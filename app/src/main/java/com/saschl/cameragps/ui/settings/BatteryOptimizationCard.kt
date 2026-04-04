package com.saschl.cameragps.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.battery_optimization_open_app_settings
import cameragps.sharednew.generated.resources.battery_optimization_open_autostart
import cameragps.sharednew.generated.resources.battery_optimization_open_failed
import cameragps.sharednew.generated.resources.battery_optimization_open_settings
import cameragps.sharednew.generated.resources.battery_optimization_settings_description
import cameragps.sharednew.generated.resources.battery_optimization_settings_short
import cameragps.sharednew.generated.resources.battery_optimization_settings_title
import cameragps.sharednew.generated.resources.learn_more
import cameragps.sharednew.generated.resources.show_less
import com.saschl.cameragps.R
import com.saschl.cameragps.utils.BatteryOptimizationUtil
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@Composable
internal fun BatteryOptimizationCard() {
    val context = LocalContext.current
    var isBatteryInfoExpanded by remember { mutableStateOf(false) }
    val openFailedText = stringResource(Res.string.battery_optimization_open_failed)

    SettingsCard(title = stringResource(Res.string.battery_optimization_settings_title)) {
        // Short description always visible (with HTML bold support)
        val shortDescHtml =
            stringResource(Res.string.battery_optimization_settings_short)
        val shortDescSpanned = remember(shortDescHtml) {
            HtmlCompat.fromHtml(shortDescHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
        Text(
            text = shortDescSpanned.toAnnotatedString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Expandable "Learn more" section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isBatteryInfoExpanded = !isBatteryInfoExpanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(
                    if (isBatteryInfoExpanded) R.drawable.expand_less_24px
                    else R.drawable.expand_more_24px
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(
                    if (isBatteryInfoExpanded) Res.string.show_less
                    else Res.string.learn_more
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        AnimatedVisibility(
            visible = isBatteryInfoExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = stringResource(Res.string.battery_optimization_settings_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Battery Optimization Button
        OutlinedButton(
            onClick = {
                try {
                    val uri = "package:${context.packageName}".toUri()
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        uri
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Timber.i("Opened battery optimization settings for package: ${context.packageName}")
                } catch (e: Exception) {
                    Timber.e(
                        e,
                        "Failed to open battery optimization settings, trying fallback"
                    )
                    try {
                        val fallbackIntent =
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(fallbackIntent)
                        Timber.i("Opened general battery optimization settings")
                    } catch (fallbackException: Exception) {
                        Timber.e(
                            fallbackException,
                            "Failed to open any battery optimization settings"
                        )
                        Toast.makeText(
                            context,
                            openFailedText,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(Res.string.battery_optimization_open_settings),
                fontWeight = FontWeight.Medium
            )
        }

        // Autostart Settings Button (vendor-specific)
        val autostartIntent =
            remember { BatteryOptimizationUtil.getResolveableComponentName(context) }

        if (autostartIntent != null) {
            OutlinedButton(
                onClick = {
                    try {
                        val uri = "package:${context.packageName}".toUri()
                        autostartIntent.apply {
                            data = uri
                        }

                        context.startActivity(autostartIntent)
                        Timber.i("Opened autostart settings")
                    } catch (e: Exception) {
                        Timber.e(
                            e,
                            "Failed to open autostart settings, trying fallback"
                        )
                        try {
                            val fallbackIntent =
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts(
                                        "package",
                                        context.packageName,
                                        null
                                    )
                                }
                            context.startActivity(fallbackIntent)
                            Timber.i("Opened app details settings as fallback")
                        } catch (fallbackException: Exception) {
                            Timber.e(
                                fallbackException,
                                "Failed to open any settings"
                            )
                            Toast.makeText(
                                context,
                                openFailedText,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(Res.string.battery_optimization_open_autostart),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // App Details Settings Button
        OutlinedButton(
            onClick = {
                try {
                    val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data =
                                Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(intent)
                    Timber.i("Opened app details settings")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to open app details settings")
                    Toast.makeText(
                        context,
                        openFailedText,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(Res.string.battery_optimization_open_app_settings),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Extension function to convert Android Spanned (HTML) to Compose AnnotatedString.
 */
private fun Spanned.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    append(this@toAnnotatedString.toString())
    getSpans(0, length, Any::class.java).forEach { span ->
        val start = getSpanStart(span)
        val end = getSpanEnd(span)
        when (span) {
            is StyleSpan -> {
                when (span.style) {
                    android.graphics.Typeface.BOLD -> addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        start,
                        end
                    )

                    android.graphics.Typeface.ITALIC -> addStyle(
                        SpanStyle(fontWeight = FontWeight.Normal),
                        start,
                        end
                    )

                    android.graphics.Typeface.BOLD_ITALIC -> addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        start,
                        end
                    )
                }
            }
        }
    }
}

