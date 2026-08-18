package com.engfred.musicplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Snappy splash screen duration (1.2s) providing a responsive launch experience.
 */
private const val MIN_SPLASH_DURATION_MS = 1_200L

@Composable
fun CustomSplashScreen(
    isReady: Boolean,
    onSplashComplete: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    var logoVisible     by remember { mutableStateOf(false) }
    var eqVisible       by remember { mutableStateOf(false) }
    var titleVisible    by remember { mutableStateOf(false) }
    var taglineVisible  by remember { mutableStateOf(false) }
    var isExiting       by remember { mutableStateOf(false) }
    var minDurationDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(40)
        logoVisible = true
        delay(120)
        eqVisible = true
        delay(120)
        titleVisible = true
        delay(140)
        taglineVisible = true
        delay(MIN_SPLASH_DURATION_MS - 420L)
        minDurationDone = true
    }

    LaunchedEffect(minDurationDone, isReady) {
        if (minDurationDone && isReady) {
            isExiting = true
            delay(400)
            onSplashComplete()
        }
    }

    val containerScale by animateFloatAsState(
        targetValue = if (isExiting) 1.04f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "splashContainerScale",
    )
    val containerAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "splashContainerAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(containerScale)
            .alpha(containerAlpha)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.background,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 1. Logo
            SplashLogo(visible = logoVisible)

//            Spacer(modifier = Modifier.height(28.dp))
//
//            // 2. Equalizer bars
//            SplashEqualizerBars(visible = eqVisible)

            Spacer(modifier = Modifier.height(24.dp))

            // 3. App title
            val titleAlpha by animateFloatAsState(
                targetValue = if (titleVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 400),
                label = "titleAlpha",
            )
            val titleOffsetY by animateFloatAsState(
                targetValue = if (titleVisible) 0f else 18f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
                label = "titleOffsetY",
            )
            Text(
                text = "F-Music",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    letterSpacing = 1.5.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .alpha(titleAlpha)
                    .offset(y = titleOffsetY.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 4. Tagline
            val taglineAlpha by animateFloatAsState(
                targetValue = if (taglineVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 500),
                label = "taglineAlpha",
            )
            Text(
                text = "Feel every beat",
                style = MaterialTheme.typography.bodyMedium.copy(
                    letterSpacing = 1.5.sp,
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.alpha(taglineAlpha),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SplashLogo(visible: Boolean) {
    val iconScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "logoScale",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "logoAlpha",
    )

    val glowTransition = rememberInfiniteTransition(label = "logoGlow")
    val glowScale by glowTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )
    val glowAlpha by glowTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Box(
        modifier = Modifier
            .scale(iconScale)
            .alpha(iconAlpha),
        contentAlignment = Alignment.Center,
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(100.dp)
                .scale(if (visible) glowScale else 1f)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = if (visible) glowAlpha else 0f
                    )
                )
        )
        // Diffuse depth ring
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                )
        )
        // Solid icon disc
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primaryContainer,
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun SplashEqualizerBars(visible: Boolean) {
    val rowAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "eqRowAlpha",
    )

    data class BarSpec(val durationMs: Int, val offsetMs: Int, val maxHeightDp: Dp)
    val barSpecs = listOf(
        BarSpec(400,   0, 20.dp),
        BarSpec(520, 100, 30.dp),
        BarSpec(360, 180, 40.dp),
        BarSpec(580,  40, 44.dp),
        BarSpec(440, 140, 40.dp),
        BarSpec(480,  70, 30.dp),
        BarSpec(350, 120, 20.dp),
    )

    val infiniteTransition = rememberInfiniteTransition(label = "eqBars")
    val barFractions = barSpecs.mapIndexed { index, spec ->
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = spec.durationMs,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(spec.offsetMs),
            ),
            label = "barFraction_$index",
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .height(48.dp)
            .alpha(rowAlpha),
    ) {
        barSpecs.forEachIndexed { index, spec ->
            val fraction by barFractions[index]
            val barHeight = spec.maxHeightDp * fraction.coerceIn(0.1f, 1f)

            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(barHeight)
                    .clip(
                        RoundedCornerShape(
                            topStart = 4.dp,
                            topEnd = 4.dp,
                            bottomStart = 2.dp,
                            bottomEnd = 2.dp,
                        )
                    )
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primaryContainer,
                            )
                        )
                    )
            )
        }
    }
}