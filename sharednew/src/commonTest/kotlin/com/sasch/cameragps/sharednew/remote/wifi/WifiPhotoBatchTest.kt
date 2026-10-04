package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WifiPhotoBatchTest {
    private val captures = groupCameraPhotos(
        listOf(
            WifiCameraPhoto(1, "ONE.ARW", 100, "image/x-sony-arw", "", captureId = "one"),
            WifiCameraPhoto(2, "ONE.HIF", 100, "image/heif", "", captureId = "one"),
            WifiCameraPhoto(3, "TWO.ARW", 100, "image/x-sony-arw", "", captureId = "two"),
            WifiCameraPhoto(4, "TWO.JPG", 100, "image/jpeg", "", captureId = "two"),
            WifiCameraPhoto(5, "LARGE.JPG", 600_000_000, "image/jpeg", "", downloadable = false),
        )
    )

    @Test
    fun selectedFormatsResolveToFilesWithoutDuplicatingPairedPhotos() {
        assertEquals(
            listOf(WifiPhotoDownload(1), WifiPhotoDownload(3)),
            photoBatchDownloads(captures, setOf(WifiPhotoBatchFormat.Raw), emptySet(), true)
        )
        assertEquals(
            listOf(WifiPhotoDownload(2, WifiPhotoDownloadFormat.Jpeg), WifiPhotoDownload(4)),
            photoBatchDownloads(
                captures,
                setOf(WifiPhotoBatchFormat.Jpeg, WifiPhotoBatchFormat.JpegCopy),
                emptySet(),
                true
            )
        )
        assertEquals(
            listOf(WifiPhotoDownload(1), WifiPhotoDownload(2)),
            photoBatchDownloads(
                captures.take(1),
                setOf(WifiPhotoBatchFormat.Raw, WifiPhotoBatchFormat.Heif),
                emptySet(),
                true
            )
        )
    }

    @Test
    fun savedOriginalDoesNotSuppressJpegCopyAndUnsupportedConversionIsExcluded() {
        val saved = setOf(WifiPhotoDownload(2))
        assertEquals(
            listOf(WifiPhotoDownload(2, WifiPhotoDownloadFormat.Jpeg)),
            photoBatchDownloads(
                captures,
                setOf(WifiPhotoBatchFormat.Heif, WifiPhotoBatchFormat.JpegCopy),
                saved,
                true
            )
        )
        assertTrue(
            photoBatchDownloads(
                captures,
                setOf(WifiPhotoBatchFormat.JpegCopy),
                emptySet(),
                false
            ).isEmpty()
        )
    }

    @Test
    fun emptySelectionAndSavedOrOversizedFilesDoNotDownload() {
        assertTrue(
            photoBatchDownloads(
                emptyList(),
                WifiPhotoBatchFormat.entries.toSet(),
                emptySet(),
                true
            ).isEmpty()
        )
        assertTrue(photoBatchDownloads(captures, emptySet(), emptySet(), true).isEmpty())
        assertTrue(
            photoBatchDownloads(
                captures,
                setOf(WifiPhotoBatchFormat.Jpeg),
                setOf(WifiPhotoDownload(4)),
                true
            ).isEmpty()
        )
    }
}
