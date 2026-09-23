package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiCameraCaptureTest {
    private fun photo(handle: Long, mime: String, captureId: String? = null) =
        WifiCameraPhoto(handle, "DSC00001.${if (mime == "image/x-sony-arw") "ARW" else "JPG"}",
            1024, mime, "2026-09-23T12:00:00", captureId = captureId)

    @Test
    fun groupsByCameraIdentityAndPrefersRawPreviewRegardlessOfFileOrder() {
        val heif = photo(1, "image/heif", "content:1:42")
        val raw = photo(2, "image/x-sony-arw", "content:1:42")
        val jpeg = photo(3, "image/jpeg", "content:1:43")
        val otherRaw = photo(4, "image/x-sony-arw", "content:1:43")
        val otherCard = photo(5, "image/jpeg", "content:2:42")
        val groups = groupCameraPhotos(listOf(heif, raw, jpeg, otherRaw, otherCard))
        assertEquals(3, groups.size)
        assertEquals(listOf(heif, raw), groups[0].files)
        assertEquals(raw, groups[0].preview)
        assertEquals(otherRaw, groups[1].preview)
        assertEquals(otherCard, groups[2].preview)
    }

    @Test
    fun matchingNamesWithoutCaptureIdentityStaySeparate() {
        assertEquals(2, groupCameraPhotos(listOf(photo(1, "image/jpeg"), photo(2, "image/x-sony-arw"))).size)
    }

    @Test
    fun pagesCountCapturesAndKeepEveryFormatTogether() {
        val captures = groupCameraPhotos((0..SonyImageTransfer.PAGE_SIZE).flatMap { index ->
            listOf(photo(index * 2L, "image/jpeg", "content:1:$index"),
                photo(index * 2L + 1, "image/x-sony-arw", "content:1:$index"))
        })
        val first = cameraPhotoPage(captures, 0)
        val last = cameraPhotoPage(captures, SonyImageTransfer.PAGE_SIZE)
        assertEquals(SonyImageTransfer.PAGE_SIZE + 1, first.totalObjects)
        assertEquals(SonyImageTransfer.PAGE_SIZE * 2, first.photos.size)
        assertTrue(first.hasMore)
        assertEquals(captures.last().files, last.photos)
        assertFalse(last.hasMore)
        assertEquals(captures.flatMap { it.files }, first.photos + last.photos)
    }
}
