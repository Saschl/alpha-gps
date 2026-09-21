package com.sasch.cameragps.sharednew.ui.components

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** A lazy list with a persistent scroll indicator and a small gutter for it. */
@Composable
fun ScrollbarLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .verticalScrollbar(state.scrollIndicatorState)
            .padding(end = 8.dp),
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Apply before verticalScroll so the indicator is drawn within the viewport. */
@Composable
fun Modifier.verticalScrollbar(state: ScrollIndicatorState?): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    return drawWithContent {
        drawContent()
        if (state == null) return@drawWithContent
        val inset = 2.dp.toPx()
        val thumb = scrollbarThumb(
            scrollOffset = state.scrollOffset,
            contentSize = state.contentSize,
            viewportSize = state.viewportSize,
            trackSize = size.height - inset * 2,
            minThumbSize = 24.dp.toPx(),
        ) ?: return@drawWithContent
        val width = 3.dp.toPx().coerceAtMost(size.width)
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            (size.width - width - inset).coerceAtLeast(0f)
        } else {
            inset.coerceAtMost(size.width - width)
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(x, inset + thumb.offset),
            size = Size(width, thumb.size),
            cornerRadius = CornerRadius(width / 2),
        )
    }
}

internal data class ScrollbarThumb(val offset: Float, val size: Float)

internal fun scrollbarThumb(
    scrollOffset: Int,
    contentSize: Int,
    viewportSize: Int,
    trackSize: Float,
    minThumbSize: Float,
): ScrollbarThumb? {
    // Compose uses MAX_VALUE for metrics that are not yet known (before measurement).
    if (scrollOffset == Int.MAX_VALUE || contentSize == Int.MAX_VALUE ||
        viewportSize == Int.MAX_VALUE || viewportSize <= 0 ||
        contentSize <= viewportSize || trackSize <= 0f
    ) return null

    val thumbSize = (trackSize * viewportSize.toFloat() / contentSize)
        .coerceIn(minThumbSize.coerceIn(0f, trackSize), trackSize)
    val progress = (scrollOffset.toFloat() / (contentSize - viewportSize)).coerceIn(0f, 1f)
    return ScrollbarThumb(offset = (trackSize - thumbSize) * progress, size = thumbSize)
}
