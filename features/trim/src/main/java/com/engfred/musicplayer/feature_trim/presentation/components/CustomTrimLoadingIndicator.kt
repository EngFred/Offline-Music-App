package com.engfred.musicplayer.feature_trim.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign

@Composable
fun CustomTrimLoadingIndicator(
    modifier: Modifier = Modifier,
    title: String = "Processing your trim…",
    subtitle: String? = "This may take a few seconds depending on file size"
) {
    // Read composable-only theme values here (outside draw scope)
    val colorSurfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val colorPrimary = MaterialTheme.colorScheme.primary
    val colorSecondary = MaterialTheme.colorScheme.secondary
    val colorTertiary = MaterialTheme.colorScheme.tertiary
    val colorOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val typography = MaterialTheme.typography

    // Animations
    val infinite = rememberInfiniteTransition()

    val rotationSlow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing))
    )
    val rotationMedium by infinite.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing))
    )
    val rotationFast by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing))
    )

    val pulse by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size((64 * pulse).dp)
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer subtle background ring drawn with Canvas (still within DrawScope, but using captured colors)
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokePx = 6f
                val radius = size.minDimension / 2f
                drawCircle(
                    color = colorSurfaceVariant,
                    radius = radius,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }

            // Rotating arcs stacked (drawBehind uses DrawScope; colors are captured above)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val stroke = Stroke(width = 6f, cap = StrokeCap.Round)

                        // arc 1 (slow)
                        rotate(degrees = rotationSlow, pivot = center) {
                            drawArc(
                                color = colorPrimary,
                                startAngle = -40f,
                                sweepAngle = 110f,
                                useCenter = false,
                                topLeft = center - Offset(size.minDimension / 2f, size.minDimension / 2f),
                                size = Size(size.minDimension, size.minDimension),
                                style = stroke
                            )
                        }

                        // arc 2 (medium)
                        rotate(degrees = rotationMedium, pivot = center) {
                            drawArc(
                                color = colorSecondary,
                                startAngle = 120f,
                                sweepAngle = 100f,
                                useCenter = false,
                                topLeft = center - Offset(size.minDimension / 2f, size.minDimension / 2f),
                                size = Size(size.minDimension, size.minDimension),
                                style = stroke
                            )
                        }

                        // arc 3 (fast)
                        rotate(degrees = rotationFast, pivot = center) {
                            drawArc(
                                color = colorTertiary,
                                startAngle = 220f,
                                sweepAngle = 80f,
                                useCenter = false,
                                topLeft = center - Offset(size.minDimension / 2f, size.minDimension / 2f),
                                size = Size(size.minDimension, size.minDimension),
                                style = stroke
                            )
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = title,
            style = typography.titleMedium
        )

        subtitle?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                textAlign = TextAlign.Center,
                text = it,
                style = typography.bodySmall,
                color = colorOnSurfaceVariant
            )
        }
    }
}
