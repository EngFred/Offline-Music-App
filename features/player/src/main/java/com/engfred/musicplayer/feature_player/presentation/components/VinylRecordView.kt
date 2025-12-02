package com.engfred.musicplayer.feature_player.presentation.components

import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay
import kotlin.math.atan2

@Composable
fun VinylRecordView(
    modifier: Modifier = Modifier,
    albumArtUri: Uri?,
    isPlaying: Boolean,
    currentSongId: Long?,
    rotationSpeedMillis: Int = 10000,
    isSeeking: Boolean = false, // Added parameter to handle seeking
    onPlayPauseToggle: () -> Unit = {},
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {}
) {
    // Logic to determine if the vinyl should visually be active.
    // We keep it active during track skips to prevent the arm from bouncing up and down.
    var isVinylActive by remember { mutableStateOf(isPlaying) }
    var lastPlayingSongId by remember { mutableStateOf(currentSongId) }

    LaunchedEffect(isPlaying, currentSongId, isSeeking) {
        // If seeking, lock the current visual state.
        // Do not react to buffering/pausing that happens under the hood during seek.
        if (isSeeking) return@LaunchedEffect

        if (isPlaying) {
            isVinylActive = true
            lastPlayingSongId = currentSongId
        } else {
            // If paused, check if ID changed (Skipping) or ID same (User Pause)
            if (currentSongId != lastPlayingSongId && currentSongId != null) {
                // Skipping track: Keep active
                isVinylActive = true
                lastPlayingSongId = currentSongId
            } else {
                // User paused: Deactivate with tiny delay to smooth frames
                delay(50)
                isVinylActive = false
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Animated Aura (Gradient Glow) - Uses visual state
        AnimatedAura(
            isPlaying = isVinylActive,
            modifier = Modifier.fillMaxSize()
        )

        // The Vinyl Record - Uses visual state for rotation
        VinylDisk(
            albumArtUri = albumArtUri,
            isPlaying = isVinylActive,
            rotationSpeedMillis = rotationSpeedMillis,
            modifier = Modifier
                .fillMaxSize(0.9f)
                .aspectRatio(1f)
        )

        // The Tonearm (Needle)
        var isDragging by remember { mutableStateOf(false) }
        var dragAngle by remember { mutableFloatStateOf(0f) }

        // Use isVinylActive for the target angle (25f vs 0f)
        val targetAngle = if (isDragging) dragAngle else if (isVinylActive) 25f else 0f

        val armRotation by animateFloatAsState(
            targetValue = targetAngle,
            animationSpec = tween(
                durationMillis = if (isDragging) 0 else 600,
                easing = FastOutSlowInEasing
            ),
            label = "tonearm_rotation"
        )

        Tonearm(
            rotationDegrees = armRotation,
            isDragging = isDragging,
            isPlaying = isVinylActive, // Visual state for pulsing effect
            onInteractionStart = { isDragging = true },
            onInteractionEnd = { finalAngle ->
                isDragging = false
                // Interaction logic still uses the REAL isPlaying state to toggle correctly
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
fun AnimatedAura(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
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

    val auraAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 0.6f else 0.2f,
        animationSpec = tween(durationMillis = 800),
        label = "aura_alpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(alpha = auraAlpha)
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2

        rotate(degrees = auraRotation, pivot = center) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF6366F1).copy(alpha = 0.3f),
                        Color(0xFF8B5CF6).copy(alpha = 0.2f),
                        Color(0xFFEC4899).copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 1.2f
                ),
                center = center,
                radius = radius * 1.2f
            )
        }
    }
}

@Composable
fun VinylDisk(
    modifier: Modifier = Modifier,
    albumArtUri: Uri?,
    isPlaying: Boolean,
    rotationSpeedMillis: Int
) {
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
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                ambientColor = Color(0xFF6366F1).copy(alpha = 0.3f),
                spotColor = Color(0xFF8B5CF6).copy(alpha = 0.3f)
            )
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A1A1A),
                        Color(0xFF0D0D0D)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF404040),
                        Color(0xFF2A2A2A),
                        Color(0xFF404040)
                    )
                ),
                shape = CircleShape
            )
            .semantics {
                contentDescription = if (isPlaying) "Vinyl record spinning" else "Vinyl record stopped"
                stateDescription = if (isPlaying) "Playing" else "Paused"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2

            // Enhanced grooves with varying depth
            for (i in 1..20) {
                val grooveRadius = radius * (0.3f + (i * 0.03f))
                val opacity = (0.15f + (i % 3) * 0.05f)
                drawCircle(
                    color = Color.White.copy(alpha = opacity),
                    radius = grooveRadius,
                    center = center,
                    style = Stroke(width = 1.5f)
                )
            }

            // Dynamic sheen effect
            rotate(degrees = -45f, pivot = center) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height)
                )
            }
        }

        // Album art with better styling
        CoilImage(
            imageModel = { albumArtUri },
            modifier = Modifier
                .fillMaxSize(0.42f)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .border(3.dp, Color(0xFF2A2A2A), CircleShape),
            imageOptions = ImageOptions(contentScale = ContentScale.Crop)
        )

        // Center spindle with gradient
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFF0F0F0),
                            Color(0xFFC0C0C0)
                        )
                    )
                )
                .border(1.5.dp, Color(0xFF1A1A1A), CircleShape)
        )
    }
}

@Composable
fun Tonearm(
    modifier: Modifier = Modifier,
    rotationDegrees: Float,
    isDragging: Boolean,
    isPlaying: Boolean,
    onInteractionStart: () -> Unit,
    onInteractionEnd: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onTap: () -> Unit
) {
    val view = LocalView.current

    // Pulsing effect when playing
    val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val interactionAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.9f else 0.0f,
        animationSpec = tween(200),
        label = "interaction_alpha"
    )

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
                        val pivotX = size.width * 0.7f
                        val pivotY = size.height * 0.1f

                        val touchX = change.position.x
                        val touchY = change.position.y

                        val deltaX = touchX - pivotX
                        val deltaY = touchY - pivotY

                        val angleRad = atan2(deltaY.toDouble(), deltaX.toDouble())
                        val angleDeg = Math.toDegrees(angleRad).toFloat()

                        val offsetDeg = 105f
                        val calculatedRotation = angleDeg - offsetDeg
                        val constrainedAngle = calculatedRotation.coerceIn(-15f, 50f)

                        onDrag(constrainedAngle)
                    }
                )
            }
            .semantics {
                contentDescription = "Tonearm control"
                stateDescription = if (isPlaying) "On record" else "Off record"
                onClick {
                    onTap()
                    true
                }
            }
    ) {
        val pivotX = size.width * 0.7f
        val pivotY = size.height * 0.1f

        // Interaction highlight circle
        if (isDragging || isPlaying) {
            drawCircle(
                color = Color(0xFF6366F1).copy(alpha = if (isDragging) interactionAlpha else pulseAlpha * 0.3f),
                radius = 35.dp.toPx(),
                center = Offset(pivotX, pivotY)
            )
        }

        rotate(degrees = rotationDegrees, pivot = Offset(pivotX, pivotY)) {
            // Enhanced base with gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFD0D0D0),
                        Color(0xFFA0A0A0)
                    )
                ),
                radius = 20.dp.toPx(),
                center = Offset(pivotX, pivotY)
            )
            drawCircle(
                color = Color(0xFF303030),
                radius = 9.dp.toPx(),
                center = Offset(pivotX, pivotY)
            )

            // Arm with metallic effect
            val armLength = size.height * 0.75f
            val armEndX = pivotX - (size.width * 0.2f)
            val armEndY = pivotY + armLength

            // Shadow for depth
            drawLine(
                color = Color.Black.copy(alpha = 0.3f),
                start = Offset(pivotX + 2.dp.toPx(), pivotY + 2.dp.toPx()),
                end = Offset(armEndX + 2.dp.toPx(), armEndY + 2.dp.toPx()),
                strokeWidth = 13.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Main arm
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF0F0F0),
                        Color(0xFFD0D0D0),
                        Color(0xFFB0B0B0)
                    ),
                    start = Offset(pivotX, pivotY),
                    end = Offset(armEndX, armEndY)
                ),
                start = Offset(pivotX, pivotY),
                end = Offset(armEndX, armEndY),
                strokeWidth = 12.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Needle head with enhanced styling
            rotate(degrees = 25f, pivot = Offset(armEndX, armEndY)) {
                val headColor = if (isDragging) {
                    Color(0xFF6366F1)
                } else if (isPlaying) {
                    Color(0xFF404040)
                } else {
                    Color(0xFF333333)
                }

                // Head shadow
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(armEndX - 14.dp.toPx(), armEndY + 2.dp.toPx()),
                    size = Size(28.dp.toPx(), 48.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx())
                )

                // Head body
                drawRoundRect(
                    color = headColor,
                    topLeft = Offset(armEndX - 15.dp.toPx(), armEndY),
                    size = Size(30.dp.toPx(), 50.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx())
                )

                // Needle detail
                drawLine(
                    color = Color(0xFFE0E0E0),
                    start = Offset(armEndX, armEndY + 50.dp.toPx()),
                    end = Offset(armEndX, armEndY + 58.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}