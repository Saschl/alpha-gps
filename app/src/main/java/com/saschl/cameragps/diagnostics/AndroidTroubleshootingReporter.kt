package com.saschl.cameragps.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.location.LocationManagerCompat
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingEmail
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingReport
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingReporter
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingSharing
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingSnapshot
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.service.location.LOCATION_PROVIDER_SELECTABLE
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.days

fun createAndroidTroubleshootingReporter(
    context: Context,
    logs: LogRepository
): TroubleshootingReporter =
    TroubleshootingReporter(logs, snapshot = {
        val services = AppServices.from(context)
        val devices = services.deviceDao.getAllCameraDevices()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED

        val power = context.getSystemService(PowerManager::class.java)
        val location = context.getSystemService(LocationManager::class.java)
        val bluetoothGranted =
            Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        TroubleshootingSnapshot(
            details = linkedMapOf(
                "App version" to "${packageInfo.versionName} (${
                    PackageInfoCompat.getLongVersionCode(
                        packageInfo
                    )
                })",
                "Distribution" to if (LOCATION_PROVIDER_SELECTABLE) "gplay" else "foss",
                "OS" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Phone model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "App enabled" to PreferencesManager.isAppEnabled(context).toString(),
                "Log level" to PreferencesManager.logLevel(context).toString(),
                "Bluetooth permission" to bluetoothGranted.toString(),
                "Bluetooth enabled" to if (bluetoothGranted) {
                    runCatching { services.bluetoothManager.adapter?.isEnabled.toString() }.getOrDefault(
                        "unknown"
                    )
                } else "permission denied",
                "Precise location permission" to granted(Manifest.permission.ACCESS_FINE_LOCATION).toString(),
                "Approximate location permission" to granted(Manifest.permission.ACCESS_COARSE_LOCATION).toString(),
                "Background location permission" to
                        (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)).toString(),
                "Location services enabled" to (location?.let {
                    LocationManagerCompat.isLocationEnabled(
                        it
                    )
                } ?: false).toString(),
                "Location provider" to if (LOCATION_PROVIDER_SELECTABLE) PreferencesManager.getLocationProvider(
                    context
                ).name else "Platform",
                "Notifications enabled" to NotificationManagerCompat.from(context)
                    .areNotificationsEnabled().toString(),
                "Battery saver" to power.isPowerSaveMode.toString(),
                "Battery optimization exempt" to power.isIgnoringBatteryOptimizations(context.packageName)
                    .toString(),
            ),
            devices = devices,
            sessions = services.orchestrator.sessions.value,
            tracking = services.orchestrator.locationManager.isActive.value,
            transmitting = services.orchestrator.locationManager.isTransmitting.value,
            lastFixAgeMillis = services.orchestrator.locationManager.lastFixAgeMillis(),
        )
    }, sharing = AndroidTroubleshootingSharing(context))

class TroubleshootingFileProvider : FileProvider()

private class AndroidTroubleshootingSharing(private val context: Context) : TroubleshootingSharing {
    override suspend fun share(report: TroubleshootingReport) {
        val archive = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "troubleshooting").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 1.days.inWholeMilliseconds
            directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val file = File(directory, "alpha-gps-report-${UUID.randomUUID()}.zip")
            try {
                writeTroubleshootingZip(file, report)
                file
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
        val uri =
            FileProvider.getUriForFile(context, "${context.packageName}.troubleshooting", archive)
        val intent = troubleshootingEmailIntent(uri, report)
        context.startActivity(Intent.createChooser(intent, null))
    }

    override fun copySummary(summary: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Alpha GPS troubleshooting summary", summary))
    }
}

internal fun troubleshootingEmailIntent(uri: Uri, report: TroubleshootingReport): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(TroubleshootingEmail.RECIPIENT))
        putExtra(Intent.EXTRA_SUBJECT, TroubleshootingEmail.SUBJECT)
        putExtra(Intent.EXTRA_TEXT, TroubleshootingEmail.body(report))
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Troubleshooting report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

internal fun writeTroubleshootingZip(file: File, report: TroubleshootingReport) {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        report.files.forEach { (name, contents) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(contents.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
