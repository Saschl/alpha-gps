package com.sasch.cameragps.sharednew.remote.wifi

internal data class WifiCameraCapture(val id: String, val files: List<WifiCameraPhoto>) {
    val previewCandidates: List<WifiCameraPhoto>
        get() = files.sortedBy {
            when (it.mimeType) {
                "image/jpeg" -> 0
                "image/heif" -> 1
                "image/x-sony-arw" -> 2
                else -> 3
            }
        }

    val preview: WifiCameraPhoto get() = previewCandidates.first()

    val thumbnail: WifiCameraPhoto
        get() = files.firstOrNull { it.mimeType == "image/x-sony-arw" } ?: preview

    val name: String get() = preview.filename.substringBeforeLast('.')
}

internal fun groupCameraPhotos(photos: List<WifiCameraPhoto>): List<WifiCameraCapture> =
    photos.groupBy { it.captureId ?: "file:${it.handle}" }
        .map { (id, files) -> WifiCameraCapture(id, files) }

internal fun cameraPhotoPage(captures: List<WifiCameraCapture>, offset: Int): CameraPhotoPage {
    require(offset in 0..captures.size)
    val end = minOf(offset + SonyImageTransfer.PAGE_SIZE, captures.size)
    return CameraPhotoPage(
        captures.subList(offset, end).flatMap { it.files }, offset, captures.size, end < captures.size
    )
}

internal val WifiCameraPhoto.formatLabel: String
    get() = when (mimeType) {
        "image/x-sony-arw" -> "RAW"
        "image/jpeg" -> "JPEG"
        "image/heif" -> "HEIF"
        else -> filename.substringAfterLast('.').uppercase()
    }
