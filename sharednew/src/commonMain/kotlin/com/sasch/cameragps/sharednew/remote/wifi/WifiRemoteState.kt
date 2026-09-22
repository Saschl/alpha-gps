package com.sasch.cameragps.sharednew.remote.wifi

/** Connection and shooting state, independent of BLE location and BLE shutter availability. */
enum class WifiRemotePhase {
    Idle,
    PreparingCamera,
    AwaitingNetworkApproval,
    JoiningNetwork,
    OpeningSession,
    Ready,
    Closing,
    Failed,
}

enum class WifiRemoteFailure {
    CameraUnavailable,
    CameraRefused,
    NetworkPermissionDenied,
    NetworkLost,
    AnotherController,
    ProtocolError,
    InvalidAddress,
    JoinCameraWifi,
    BluetoothRequired,
    CameraSetupFailed,
    NetworkJoinFailed,
    CameraAddressUnavailable,
    WifiDisabled,
    LocationServicesRequired,
}

enum class WifiCaptureStatus { Idle, Shooting, Captured, Uncertain, Rejected }
enum class WifiPreviewStatus { Waiting, Streaming, Failed }
enum class WifiShutdownStatus { NotRequested, Requested, Unavailable, Unconfirmed }

data class WifiRemoteState(
    val phase: WifiRemotePhase = WifiRemotePhase.Idle,
    val failure: WifiRemoteFailure? = null,
    val canCaptureStill: Boolean = false,
    val hasLiveView: Boolean = false,
    val cameraName: String = "",
    val capture: WifiCaptureStatus = WifiCaptureStatus.Idle,
    val preview: WifiPreviewStatus = WifiPreviewStatus.Waiting,
    val wifiShutdown: WifiShutdownStatus = WifiShutdownStatus.NotRequested,
) {
    init {
        require((phase == WifiRemotePhase.Failed) == (failure != null))
        require(phase == WifiRemotePhase.Ready || (!canCaptureStill && !hasLiveView))
    }
}
