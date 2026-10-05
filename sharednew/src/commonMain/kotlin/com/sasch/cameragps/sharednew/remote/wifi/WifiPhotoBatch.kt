package com.sasch.cameragps.sharednew.remote.wifi

internal enum class WifiPhotoBatchFormat(
    val mimeType: String,
    val format: WifiPhotoDownloadFormat = WifiPhotoDownloadFormat.Original
) {
    Jpeg("image/jpeg"),
    Raw("image/x-sony-arw"),
    Heif("image/heif"),
    JpegCopy("image/heif", WifiPhotoDownloadFormat.Jpeg),
}

internal fun photoBatchDownloads(
    captures: List<WifiCameraCapture>,
    formats: Set<WifiPhotoBatchFormat>,
    saved: Set<WifiPhotoDownload>,
    canConvertHeif: Boolean,
): List<WifiPhotoDownload> = captures.flatMap { capture ->
    capture.files.filter { it.downloadable }.flatMap { file ->
        formats.filter { it.mimeType == file.mimeType && (it != WifiPhotoBatchFormat.JpegCopy || canConvertHeif) }
            .map { WifiPhotoDownload(file.handle, it.format) }
    }
}.distinct().filterNot { it in saved }
