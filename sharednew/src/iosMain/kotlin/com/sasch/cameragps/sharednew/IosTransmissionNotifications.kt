package com.sasch.cameragps.sharednew

import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.ios_transmission_notification_body
import cameragps.sharednew.generated.resources.ios_transmission_notification_title
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.notification.TransmissionNotificationCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState.UIApplicationStateActive
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * App-scoped iOS status notifications. Started from didFinishLaunching, including
 * restoration launches; presentation does not depend on Compose being attached.
 * Native callbacks only send messages/resume waiters; state stays on the main scope.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosTransmissionNotifications(
    private val scope: CoroutineScope,
    private val sessions: StateFlow<Map<String, CameraSession>>,
    private val transmitting: StateFlow<Boolean>,
) {
    private val log = logging()
    private val _enabled = MutableStateFlow(IosAppPreferences.isTransmissionNotificationsEnabled())
    val enabled: StateFlow<Boolean> = _enabled
    private val authorized = MutableStateFlow(false)
    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied
    private val permissionRequests = Channel<Boolean>(Channel.UNLIMITED)
    private val presentations = Channel<Presentation>(Channel.UNLIMITED)
    private var coordinator: TransmissionNotificationCoordinator? = null
    private var activeObserver: Any? = null
    private var started = false
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    private data class Presentation(
        val isTransmissionStatus: Boolean,
        val completion: (UNNotificationPresentationOptions) -> Unit,
    )

    // UNUserNotificationCenter holds its delegate weakly; retain it for the app lifetime.
    private val delegate = object : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            val message = Presentation(
                willPresentNotification.request.identifier == REQUEST_ID,
                withCompletionHandler,
            )
            if (presentations.trySend(message).isFailure) withCompletionHandler(0uL)
        }
    }

    fun start() {
        if (started) return
        started = true
        center.delegate = delegate
        scope.launch {
            for (presentation in presentations) {
                // Keep this silent in the foreground. Other notifications retain
                // the previous default behavior (no foreground presentation).
                val visible = presentation.isTransmissionStatus &&
                        (coordinator?.visibleCameraCount?.value ?: 0) > 0
                presentation.completion(if (visible) UNNotificationPresentationOptionList else 0uL)
            }
        }
        coordinator = TransmissionNotificationCoordinator(
            scope, sessions, transmitting, enabled, authorized,
            object : TransmissionNotificationCoordinator.Publisher {
                override suspend fun show(cameraCount: Int) = post(cameraCount)
                override fun showIdle() {
                    center.removePendingNotificationRequestsWithIdentifiers(listOf(REQUEST_ID))
                    center.removeDeliveredNotificationsWithIdentifiers(listOf(REQUEST_ID))
                }
            },
        )
        coordinator!!.start()
        activeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification, null, null,
        ) { permissionRequests.trySend(false) }
        scope.launch {
            for (explicitRequest in permissionRequests) refreshPermission(explicitRequest)
        }
        scope.launch {
            combine(enabled, coordinator!!.transmittingCameraCount) { on, count -> on && count > 0 }
                .distinctUntilChanged()
                .collect { active -> if (active) permissionRequests.trySend(false) }
        }
        // Clear stale status at startup and load authorization even on a headless launch.
        permissionRequests.trySend(false)
    }

    fun setEnabled(value: Boolean) {
        IosAppPreferences.setTransmissionNotificationsEnabled(value)
        _enabled.value = value
        if (value) permissionRequests.trySend(true)
    }

    private data class Permission(
        val allowed: Boolean,
        val denied: Boolean,
        val notDetermined: Boolean
    )

    private suspend fun readPermission(): Permission = suspendCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            continuation.resume(
                Permission(
                    allowed = status == UNAuthorizationStatusAuthorized ||
                            status == UNAuthorizationStatusProvisional ||
                            status == UNAuthorizationStatusEphemeral,
                    denied = status == UNAuthorizationStatusDenied,
                    notDetermined = status == UNAuthorizationStatusNotDetermined,
                )
            )
        }
    }

    private suspend fun refreshPermission(explicitRequest: Boolean) {
        var permission = readPermission()
        val active = (coordinator?.transmittingCameraCount?.value ?: 0) > 0
        if (enabled.value && permission.notDetermined && (explicitRequest || active) &&
            UIApplication.sharedApplication.applicationState == UIApplicationStateActive
        ) {
            suspendCoroutine<Unit> { continuation ->
                center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert) { _, error ->
                    if (error != null) log.w { "Notification authorization failed: ${error.localizedDescription}" }
                    continuation.resume(Unit)
                }
            }
            permission = readPermission()
        }
        authorized.value = permission.allowed
        _permissionDenied.value = permission.denied
    }

    private suspend fun post(cameraCount: Int) {
        val content = UNMutableNotificationContent().apply {
            setTitle(getString(Res.string.ios_transmission_notification_title))
            setBody(
                getPluralString(
                    Res.plurals.ios_transmission_notification_body,
                    cameraCount,
                    cameraCount
                )
            )
            setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive)
            // No sound or badge: this is current status, not an alert requiring action.
        }
        // Resources are loaded asynchronously. Skip a superseded count before posting.
        if (coordinator?.visibleCameraCount?.value != cameraCount) return
        val request = UNNotificationRequest.requestWithIdentifier(REQUEST_ID, content, null)
        suspendCoroutine<Unit> { continuation ->
            center.addNotificationRequest(request) { error ->
                if (error != null) log.w { "Could not post transmission status: ${error.localizedDescription}" }
                continuation.resume(Unit)
            }
        }
    }

    private companion object {
        const val REQUEST_ID = "com.saschl.cameragps.transmissionStatus"
    }
}
