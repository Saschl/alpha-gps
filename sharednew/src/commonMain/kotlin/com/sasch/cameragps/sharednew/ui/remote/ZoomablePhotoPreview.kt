package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize

private const val MAX_PHOTO_ZOOM = 5f

@Composable
fun ZoomablePhotoPreview(
    image: ImageBitmap,
    contentDescription: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(Size.Zero) }
    val fit = minOf(viewport.width / image.width, viewport.height / image.height)
    val fittedImage = Size(image.width * fit, image.height * fit)
    var scale by remember(active) { mutableFloatStateOf(1f) }
    var pan by remember(active) { mutableStateOf(Offset.Zero) }
    fun transform(centroid: Offset, targetScale: Float, panChange: Offset = Offset.Zero) {
        val nextScale = targetScale.coerceIn(1f, MAX_PHOTO_ZOOM)
        val pivot = if (centroid.isSpecified) centroid - viewport.center else Offset.Zero
        val start = pan.constrainToImage(fittedImage, viewport, scale)
        // Keep the point of the photo under the gesture fixed while zooming.
        pan = (pivot + (start - pivot) * (nextScale / scale) + panChange)
            .constrainToImage(fittedImage, viewport, nextScale)
        scale = nextScale
    }
    val gesture = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        transform(centroid, scale * zoomChange, panChange)
    }
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .onSizeChanged { viewport = it.toSize() }
            .clipToBounds()
            .pointerInput(active, viewport, fittedImage) {
                if (active) detectTapGestures(onDoubleTap = { position ->
                    transform(position, if (scale >= MAX_PHOTO_ZOOM) 1f else scale * 2f)
                })
            }
            .transformable(gesture, canPan = { scale > 1f }, enabled = active)
            .graphicsLayer {
                val translation = pan.constrainToImage(fittedImage, viewport, scale)
                scaleX = scale
                scaleY = scale
                translationX = translation.x
                translationY = translation.y
            }
    )
}

private fun Offset.constrainToImage(image: Size, viewport: Size, scale: Float): Offset {
    val maxX = ((image.width * scale - viewport.width) / 2).coerceAtLeast(0f)
    val maxY = ((image.height * scale - viewport.height) / 2).coerceAtLeast(0f)
    return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}
