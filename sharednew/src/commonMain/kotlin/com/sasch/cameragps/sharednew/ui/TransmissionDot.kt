package com.sasch.cameragps.sharednew.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun TransmissionDot(
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(12.dp), // fixed layout size so nothing moves
        contentAlignment = Alignment.Center
    ) {
        if (!isRunning) {  // Static red dot when disabled
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = Color.Red,
                        shape = CircleShape
                    )
            )
            return
        }

        val infiniteTransition = rememberInfiniteTransition(label = "txDot")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "txDotScale"
        )

        Box(
            modifier = modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    color = Color.Green,
                    shape = CircleShape
                )
        )
    }
}