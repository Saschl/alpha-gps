package com.sasch.cameragps.sharednew.remote.wifi

/** Connection and shooting state, independent of BLE location and BLE shutter availability. */
enum class WifiRemotePhase {
    Idle,
    PreparingCamera,
    AwaitingNetworkApproval,
    AwaitingManualNetwork,
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
    CameraCredentialsUnavailable,
    NetworkJoinFailed,
    CameraAddressUnavailable,
    WifiDisabled,
    LocationServicesRequired,
}

enum class WifiCaptureStatus { Idle, Shooting, Captured, Uncertain, Rejected }
enum class WifiPreviewStatus { Waiting, Streaming, Failed }
enum class WifiShutdownStatus { NotRequested, Requested, Unavailable, Unconfirmed }

enum class WifiImageTransferStatus { Idle, Downloading, Converting, Saved, Cancelled, Failed, ConversionFailed, PermissionDenied }

enum class WifiPhotoDownloadFormat { Original, Jpeg }

data class WifiPhotoDownload(val handle: Long, val format: WifiPhotoDownloadFormat = WifiPhotoDownloadFormat.Original)

data class WifiImageTransferState(
    val status: WifiImageTransferStatus = WifiImageTransferStatus.Idle,
    val filename: String = "",
    val bytesReceived: Long = 0,
    val totalBytes: Long = 0,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
) {
    val busy: Boolean get() = status == WifiImageTransferStatus.Downloading || status == WifiImageTransferStatus.Converting
}

data class WifiCameraPhoto(
    val handle: Long,
    val filename: String,
    val size: Long,
    val mimeType: String,
    val capturedAt: String,
    val downloadable: Boolean = true,
    val captureId: String? = null,
)

data class WifiPhotoBrowserState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val photos: List<WifiCameraPhoto> = emptyList(),
    val offset: Int = 0,
    val totalObjects: Int = 0,
    val hasMore: Boolean = false,
    val savedDownloads: Set<WifiPhotoDownload> = emptySet(),
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
    val canConvertHeif: Boolean = false,
    val photoBrowser: WifiPhotoBrowserState = WifiPhotoBrowserState(),
    val imageTransfer: WifiImageTransferState = WifiImageTransferState(),
    val wifiShutdown: WifiShutdownStatus = WifiShutdownStatus.NotRequested,
    val manualNetwork: CameraWifiCredentials? = null,
) {
    init {
        require((phase == WifiRemotePhase.Failed) == (failure != null))
        require(phase == WifiRemotePhase.Ready || (!canCaptureStill && !hasLiveView))
    }
}
