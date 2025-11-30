package com.engfred.musicplayer.feature_player.presentation.components

import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlin.math.atan2

@Composable
fun VinylRecordView(
    modifier: Modifier = Modifier,
    albumArtUri: Uri?,
    isPlaying: Boolean,
    rotationSpeedMillis: Int = 10000,
    onPlayPauseToggle: () -> Unit = {},
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {}
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // --- 0. Animated Aura (Gradient Glow) ---
        // Rotates the gradient colors behind the record for a "living" effect
        val infiniteTransition = rememberInfiniteTransition(label = "aura_rotation")
        val auraRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 15000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "aura_spin"
        )

        // --- 1. The Vinyl Record ---
        VinylDisk(
            albumArtUri = albumArtUri,
            isPlaying = isPlaying,
            rotationSpeedMillis = rotationSpeedMillis,
            modifier = Modifier
                .fillMaxSize(0.9f)
                .aspectRatio(1f)
        )

        // --- 2. The Tonearm (Needle) ---
        var isDragging by remember { mutableStateOf(false) }
        var dragAngle by remember { mutableFloatStateOf(0f) }

        // Logic:
        // 25 degrees = Playing (on the record)
        // 0 degrees = Paused (off the record)
        val targetAngle = if (isDragging) dragAngle else if (isPlaying) 25f else 0f

        // When dragging, duration is 0 (instant snap). When auto-playing, smooth tween.
        val armRotation by animateFloatAsState(
            targetValue = targetAngle,
            animationSpec = tween(
                durationMillis = if (isDragging) 0 else 600,
                easing = LinearEasing
            ),
            label = "tonearm_rotation"
        )

        Tonearm(
            rotationDegrees = armRotation,
            isDragging = isDragging,
            onInteractionStart = { isDragging = true },
            onInteractionEnd = { finalAngle ->
                isDragging = false
                // Logic: > 15 deg means user dropped it ON the record -> PLAY
                //        < 15 deg means user dropped it OFF the record -> PAUSE
                if (finalAngle > 15f) {
                    if (!isPlaying) onPlay()
                } else {
                    if (isPlaying) onPause()
                }
            },
            onDrag = { angle -> dragAngle = angle },
            onTap = onPlayPauseToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(100.dp, 180.dp)
                .offset(x = (-10).dp, y = (-20).dp)
        )
    }
}

@Composable
fun VinylDisk(
    modifier: Modifier = Modifier,
    albumArtUri: Uri?,
    isPlaying: Boolean,
    rotationSpeedMillis: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
    val rotationAnim = remember { Animatable(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            rotationAnim.animateTo(
                targetValue = rotationAnim.value + 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(rotationSpeedMillis, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        } else {
            rotationAnim.stop()
        }
    }

    Box(
        modifier = modifier
            .rotate(rotationAnim.value)
            .shadow(elevation = 12.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF101010))
            .border(1.dp, Color(0xFF333333), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2

            // Grooves
            for (i in 1..15) {
                drawCircle(
                    color = Color(0xFF222222),
                    radius = radius * (0.3f + (i * 0.04f)),
                    center = center,
                    style = Stroke(width = 2f)
                )
            }

            // Sheen
            rotate(degrees = -45f, pivot = center) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.1f), Color.Transparent)
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height)
                )
            }
        }

        CoilImage(
            imageModel = { albumArtUri },
            modifier = Modifier
                .fillMaxSize(0.4f)
                .clip(CircleShape)
                .border(2.dp, Color(0xFF222222), CircleShape),
            imageOptions = ImageOptions(contentScale = ContentScale.Crop)
        )

        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFFE0E0E0))
                .border(1.dp, Color.Black, CircleShape)
        )
    }
}

@Composable
fun Tonearm(
    modifier: Modifier = Modifier,
    rotationDegrees: Float,
    isDragging: Boolean,
    onInteractionStart: () -> Unit,
    onInteractionEnd: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onTap: () -> Unit
) {
    val view = LocalView.current

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTap()
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        onInteractionStart()
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    },
                    onDragEnd = { onInteractionEnd(rotationDegrees) },
                    onDragCancel = { onInteractionEnd(rotationDegrees) },
                    onDrag = { change, _ ->
                        // --- MATHEMATICAL FIX FOR SHAKING ---
                        // Instead of accumulating small changes, we calculate the absolute angle
                        // of the finger relative to the pivot point.

                        // 1. Identify Pivot (Top-Right-ish based on drawing logic below)
                        // In drawing: pivotX = width * 0.7, pivotY = height * 0.1
                        val pivotX = size.width * 0.7f
                        val pivotY = size.height * 0.1f

                        // 2. Get Touch Position
                        val touchX = change.position.x
                        val touchY = change.position.y

                        // 3. Calculate Vector from Pivot to Touch
                        val deltaX = touchX - pivotX
                        val deltaY = touchY - pivotY

                        // 4. Calculate Angle in Radians using atan2(y, x)
                        val angleRad = atan2(deltaY.toDouble(), deltaX.toDouble())

                        // 5. Convert to Degrees
                        val angleDeg = Math.toDegrees(angleRad).toFloat()

                        // 6. Normalize relative to the "Zero" position of the arm.
                        // The arm naturally points down-left.
                        // Drawing logic: Line goes from Pivot to (PivotX - 0.2W, PivotY + Length).
                        // That vector is approx ( -0.2, 0.75 ).
                        // The angle of that vector is approx 105 degrees (90 is down, >90 is left).
                        // So, we subtract ~105 degrees so that if the finger is exactly along that line, the result is 0.
                        val offsetDeg = 105f
                        val calculatedRotation = angleDeg - offsetDeg

                        // 7. Clamp values so user can't spin it 360
                        val constrainedAngle = calculatedRotation.coerceIn(-15f, 50f)

                        onDrag(constrainedAngle)
                    }
                )
            }
    ) {
        val pivotX = size.width * 0.7f
        val pivotY = size.height * 0.1f

        rotate(degrees = rotationDegrees, pivot = Offset(pivotX, pivotY)) {
            // Base
            drawCircle(color = Color(0xFFC0C0C0), radius = 18.dp.toPx(), center = Offset(pivotX, pivotY))
            drawCircle(color = Color(0xFF404040), radius = 8.dp.toPx(), center = Offset(pivotX, pivotY))

            // Arm
            val armLength = size.height * 0.75f
            drawLine(
                color = Color(0xFFE0E0E0),
                start = Offset(pivotX, pivotY),
                end = Offset(pivotX - (size.width * 0.2f), pivotY + armLength),
                strokeWidth = 12.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Head (Needle Holder)
            val headX = pivotX - (size.width * 0.2f)
            val headY = pivotY + armLength

            rotate(degrees = 25f, pivot = Offset(headX, headY)) {
                // Highlight head if dragging
                val headColor = if (isDragging) Color(0xFF555555) else Color(0xFF333333)
                drawRoundRect(
                    color = headColor,
                    topLeft = Offset(headX - 15.dp.toPx(), headY),
                    size = Size(30.dp.toPx(), 50.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
        }
    }
}