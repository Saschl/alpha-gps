package com.sasch.cameragps.sharednew.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keeps a single content column readable as the window grows. */
@Composable
fun ScreenContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 720.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(),
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

/** Full-width on phones; aligned with the content's leading edge in wider panes. */
@Composable
fun AdaptiveAction(
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val actionWidth = if (maxWidth >= 600.dp) 360.dp else maxWidth
        content(Modifier.widthIn(max = actionWidth).fillMaxWidth())
    }
}
