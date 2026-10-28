package com.sasch.cameragps.sharednew.diagnostics

import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBManager
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingForUploading
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.timeIntervalSinceNow
import platform.Foundation.writeToFile
import platform.MessageUI.MFMailComposeResult
import platform.MessageUI.MFMailComposeViewController
import platform.MessageUI.MFMailComposeViewControllerDelegateProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalForeignApi::class)
internal fun createIosTroubleshootingReporter(
    logs: LogRepository,
    presenter: UIViewController,
): TroubleshootingReporter = TroubleshootingReporter(logs, snapshot = {
    val controller = IosBluetoothController
    val bundle = NSBundle.mainBundle
    val phone = UIDevice.currentDevice
    val authorization = when (CLLocationManager.authorizationStatus()) {
        kCLAuthorizationStatusAuthorizedAlways -> "Always"
        kCLAuthorizationStatusAuthorizedWhenInUse -> "When in use"
        kCLAuthorizationStatusDenied -> "Denied"
        kCLAuthorizationStatusRestricted -> "Restricted"
        kCLAuthorizationStatusNotDetermined -> "Not requested"
        else -> "Unknown"
    }
    controller.troubleshootingSnapshot(
        linkedMapOf(
            "App version" to "${bundle.objectForInfoDictionaryKey("CFBundleShortVersionString")} (${
                bundle.objectForInfoDictionaryKey(
                    "CFBundleVersion"
                )
            })",
            "OS" to "${phone.systemName} ${phone.systemVersion}",
            "Phone model" to phone.model,
            "App enabled" to IosAppPreferences.isAppEnabled().toString(),
            "Log level" to IosAppPreferences.getLogLevel(),
            "Bluetooth authorization" to CBManager.authorization.toString(),
            "Bluetooth powered on" to controller.bluetoothPoweredOn.value.toString(),
            "Location authorization" to authorization,
            "Precise location permission" to controller.hasPreciseAccuracyAuthorization()
                .toString(),
            "Location services enabled" to CLLocationManager.locationServicesEnabled().toString(),
            "Transmission notifications enabled" to controller.transmissionNotificationsEnabled.value.toString(),
            "Notification permission denied" to controller.transmissionNotificationsPermissionDenied.value.toString(),
            "Accessory migration pending" to controller.migrationCandidates.value.size.toString(),
            "Accessory migration in progress" to controller.migrationInProgress.value.toString(),
        )
    )
}, sharing = IosTroubleshootingSharing(presenter))

@OptIn(ExperimentalForeignApi::class)
private class IosTroubleshootingSharing(private val presenter: UIViewController) :
    TroubleshootingSharing {
    // MessageUI holds its delegate weakly, so keep it alive with the sharing adapter.
    private val mailDelegate = ReportMailDelegate()

    override suspend fun share(report: TroubleshootingReport) {
        val archive = withContext(Dispatchers.IO) { createIosTroubleshootingArchive(report) }
        check(presenter.view.window != null && presenter.presentedViewController == null) {
            "The share sheet cannot be presented right now"
        }
        if (MFMailComposeViewController.canSendMail()) {
            val attachment = withContext(Dispatchers.IO) {
                checkNotNull(NSFileManager.defaultManager.contentsAtPath(checkNotNull(archive.path)))
            }
            val mail = MFMailComposeViewController().apply {
                mailComposeDelegate = mailDelegate
                setToRecipients(listOf(TroubleshootingEmail.RECIPIENT))
                setSubject(TroubleshootingEmail.SUBJECT)
                setMessageBody(TroubleshootingEmail.body(report), isHTML = false)
                addAttachmentData(
                    attachment,
                    mimeType = "application/zip",
                    fileName = "alpha-gps-report.zip"
                )
            }
            presenter.presentViewController(mail, animated = true, completion = null)
            return
        }
        val sheet = UIActivityViewController(
            activityItems = listOf(archive, TroubleshootingEmail.shareText(report)),
            applicationActivities = null,
        )
        sheet.popoverPresentationController?.apply {
            sourceView = presenter.view
            sourceRect = presenter.view.bounds
            permittedArrowDirections = 0u
        }
        presenter.presentViewController(sheet, animated = true, completion = null)
    }

    override fun copySummary(summary: String) {
        UIPasteboard.generalPasteboard.string = summary
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class ReportMailDelegate : NSObject(), MFMailComposeViewControllerDelegateProtocol {
    override fun mailComposeController(
        controller: MFMailComposeViewController,
        didFinishWithResult: MFMailComposeResult,
        error: NSError?,
    ) {
        controller.dismissViewControllerAnimated(true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun createIosTroubleshootingArchive(report: TroubleshootingReport): NSURL {
    val files = NSFileManager.defaultManager
    val root = NSTemporaryDirectory() + "troubleshooting"
    check(
        files.createDirectoryAtPath(
            root,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    )
    files.contentsOfDirectoryAtPath(root, error = null)?.filterIsInstance<String>()
        ?.forEach { name ->
            val path = "$root/$name"
            val modified = files.attributesOfItemAtPath(path, error = null)
                ?.get(NSFileModificationDate) as? NSDate
            if (modified != null && -modified.timeIntervalSinceNow > 1.days.inWholeSeconds) {
                files.removeItemAtPath(path, error = null)
            }
        }
    val id = NSUUID().UUIDString
    val input = "$root/$id"
    val output = NSURL.fileURLWithPath("$root/alpha-gps-report-$id.zip")
    check(
        files.createDirectoryAtPath(
            input,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    )
    try {
        report.files.forEach { (name, contents) ->
            check(
                NSString.create(string = contents).writeToFile(
                    "$input/$name",
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = null,
                )
            ) { "Could not write report" }
        }
        memScoped {
            var copied = false
            val error = alloc<ObjCObjectVar<NSError?>>().apply { value = null }
            val copyError = alloc<ObjCObjectVar<NSError?>>().apply { value = null }
            // Foundation creates a ZIP snapshot of a directory; retain it before the accessor returns.
            NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
                NSURL.fileURLWithPath(input, isDirectory = true),
                options = NSFileCoordinatorReadingForUploading,
                error = error.ptr,
            ) { archive ->
                if (archive != null) copied =
                    files.copyItemAtURL(archive, toURL = output, error = copyError.ptr)
            }
            check(copied) { "Could not create report archive: ${error.value ?: copyError.value}" }
        }
        return output
    } catch (error: Exception) {
        files.removeItemAtURL(output, error = null)
        throw error
    } finally {
        files.removeItemAtPath(input, error = null)
    }
}
