package com.saschl.cameragps.ui.device

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.sasch.cameragps.sharednew.ui.remote.ZoomablePhotoPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class WifiPhotoZoomTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var pager: PagerState
    private val previewReady = mutableStateOf(false)
    private val viewer get() = compose.onNodeWithTag("viewer")

    private fun showViewer(height: Int = 64) {
        val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow).map { it.toArgb() }
        fun stripes(width: Int, height: Int) = Bitmap.createBitmap(
            IntArray(width * height) { colors[(it % width) * 4 / width] },
            width, height, Bitmap.Config.ARGB_8888
        ).asImageBitmap()
        val thumbnail = stripes(64, height)
        val preview = stripes(256, height * 4)
        compose.setContent {
            pager = rememberPagerState { 2 }
            HorizontalPager(pager, Modifier.size(240.dp).background(Color.Black).testTag("viewer")) { page ->
                ZoomablePhotoPreview(
                    if (previewReady.value) preview else thumbnail, "Photo $page",
                    active = page == pager.settledPage, modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun pixel(x: Float, y: Float = 0.5f): Int {
        val image = viewer.captureToImage()
        return image.toPixelMap()[(image.width * x).toInt(), (image.height * y).toInt()].toArgb()
    }

    private fun pinchOut() = viewer.performTouchInput {
        pinch(
            Offset(width * .18f, centerY), Offset(width * .06f, centerY),
            Offset(width * .32f, centerY), Offset(width * .44f, centerY)
        )
    }

    private fun doubleTapAt(x: Float) = viewer.performTouchInput {
        doubleClick(Offset(width * x, centerY))
    }

    @Test
    fun doubleTapZoomsAroundTheTapAndReturnsToFitAfterFiveTimes() {
        showViewer()
        compose.runOnIdle { previewReady.value = true }
        doubleTapAt(.2f)
        assertEquals(Color.Green.toArgb(), pixel(.55f))
        assertEquals(Color.Green.toArgb(), pixel(.35f))
        doubleTapAt(.2f)
        assertEquals(Color.Red.toArgb(), pixel(.35f))
        assertEquals(Color.Green.toArgb(), pixel(.425f))
        doubleTapAt(.2f)
        assertEquals(Color.Red.toArgb(), pixel(.425f))

        doubleTapAt(.8f)
        assertEquals(Color.Red.toArgb(), pixel(.1f))
        assertEquals(Color.Yellow.toArgb(), pixel(.9f))
        viewer.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, pager.settledPage) }
    }

    @Test
    fun doubleTapReturnsToFitAfterPinchingToMaximum() {
        showViewer()
        viewer.performTouchInput {
            pinch(
                Offset(width * .48f, centerY), Offset(width * .05f, centerY),
                Offset(width * .52f, centerY), Offset(width * .95f, centerY)
            )
        }
        assertEquals(Color.Green.toArgb(), pixel(.1f))
        doubleTapAt(.2f)
        assertEquals(Color.Red.toArgb(), pixel(.1f))
        assertEquals(Color.Yellow.toArgb(), pixel(.9f))
    }

    @Test
    fun pinchAnchorsAtFingersAndDragPansUntilPinchedBackToFit() {
        showViewer()
        assertEquals(Color.Blue.toArgb(), pixel(.55f))
        pinchOut()
        assertEquals(Color.Green.toArgb(), pixel(.55f))
        compose.runOnIdle { previewReady.value = true }
        assertEquals(Color.Green.toArgb(), pixel(.55f))
        viewer.performTouchInput { swipeLeft() }
        assertNotEquals(Color.Green.toArgb(), pixel(.55f))
        compose.runOnIdle { assertEquals(0, pager.settledPage) }

        viewer.performTouchInput {
            pinch(
                Offset(width * .05f, centerY), Offset(width * .46f, centerY),
                Offset(width * .95f, centerY), Offset(width * .54f, centerY)
            )
        }
        assertEquals(Color.Blue.toArgb(), pixel(.55f))
        viewer.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, pager.settledPage) }
    }

    @Test
    fun widePhotoStaysCenteredOnTheAxisThatStillFits() {
        showViewer(height = 16)
        pinchOut()
        viewer.performTouchInput { swipeDown() }
        assertEquals(Color.Green.toArgb(), pixel(.55f))
        assertEquals(Color.Black.toArgb(), pixel(.55f, .05f))
        assertEquals(Color.Black.toArgb(), pixel(.55f, .95f))
    }
}
