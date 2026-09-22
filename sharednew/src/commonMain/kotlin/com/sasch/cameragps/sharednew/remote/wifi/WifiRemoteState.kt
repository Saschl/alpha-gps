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

enum class WifiImageTransferStatus { Idle, Downloading, Saved, Cancelled, Failed, PermissionDenied }

data class WifiImageTransferState(
    val status: WifiImageTransferStatus = WifiImageTransferStatus.Idle,
    val filename: String = "",
    val bytesReceived: Long = 0,
    val totalBytes: Long = 0,
) {
    val busy: Boolean get() = status == WifiImageTransferStatus.Downloading
}

data class WifiCameraPhoto(
    val handle: Long,
    val filename: String,
    val size: Long,
    val mimeType: String,
    val capturedAt: String,
    val downloadable: Boolean = true,
)

data class WifiPhotoBrowserState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val photos: List<WifiCameraPhoto> = emptyList(),
    val offset: Int = 0,
    val totalObjects: Int = 0,
    val hasMore: Boolean = false,
    val savedHandles: Set<Long> = emptySet(),
)

data class WifiRemoteState(
    val phase: WifiRemotePhase = WifiRemotePhase.Idle,
    val failure: WifiRemoteFailure? = null,
    val canCaptureStill: Boolean = false,
    val hasLiveView: Boolean = false,
    val cameraName: String = "",
    val capture: WifiCaptureStatus = WifiCaptureStatus.Idle,
    val preview: WifiPreviewStatus = WifiPreviewStatus.Waiting,
    val canTransferImages: Boolean = false,
    val photoBrowser: WifiPhotoBrowserState = WifiPhotoBrowserState(),
    val imageTransfer: WifiImageTransferState = WifiImageTransferState(),
    val wifiShutdown: WifiShutdownStatus = WifiShutdownStatus.NotRequested,
) {
    init {
        require((phase == WifiRemotePhase.Failed) == (failure != null))
        require(phase == WifiRemotePhase.Ready || (!canCaptureStill && !hasLiveView))
    }
}
