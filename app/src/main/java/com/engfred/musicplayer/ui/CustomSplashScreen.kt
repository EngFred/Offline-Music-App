package com.engfred.musicplayer.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Duration (ms) the splash screen is shown regardless of how fast the app loads.
 * Keeps the branding visible even on fast devices / cached state.
 */
private const val MIN_SPLASH_DURATION_MS = 3_000L

/**
 * Custom animated splash screen shown after the native splash screen while the
 * app settings are loading.
 *
 * Entrance phases:
 *  1. Logo icon springs in with a pulsing glow ring
 *  2. Equalizer bars begin their organic beat animation
 *  3. App name slides upward and fades in
 *  4. Tagline cross-fades in
 *
 * Exit: the whole container scales slightly out while fading, then [onSplashComplete]
 * is invoked to hand control back to [MainActivity].
 *
 * @param isReady   True once the app settings have finished loading.
 * @param onSplashComplete Callback invoked after both [MIN_SPLASH_DURATION_MS] has
 *                         elapsed AND [isReady] is true, following the exit animation.
 */
@Composable
fun CustomSplashScreen(
    isReady: Boolean,
    onSplashComplete: () -> Unit,
) {

    // ─── Orientation Lock ───────────────────────────────────────────────────
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        // Save the original orientation to restore it later
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Lock the screen to portrait mode
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        onDispose {
            // Restore the original orientation when the splash screen is removed
            activity?.requestedOrientation = originalOrientation
        }
    }

    // ─── animation-phase flags ──────────────────────────────────────────────
    var logoVisible     by remember { mutableStateOf(false) }
    var eqVisible       by remember { mutableStateOf(false) }
    var titleVisible    by remember { mutableStateOf(false) }
    var taglineVisible  by remember { mutableStateOf(false) }
    var isExiting       by remember { mutableStateOf(false) }
    var minDurationDone by remember { mutableStateOf(false) }

    // Stagger entrance phases on first composition
    LaunchedEffect(Unit) {
        delay(80)
        logoVisible = true
        delay(260)
        eqVisible = true
        delay(220)
        titleVisible = true
        delay(300)
        taglineVisible = true
        // Remaining time until minimum duration has elapsed
        delay(MIN_SPLASH_DURATION_MS - 860L)
        minDurationDone = true
    }

    // Once minimum time is done AND the app is ready, trigger the exit sequence
    LaunchedEffect(minDurationDone, isReady) {
        if (minDurationDone && isReady) {
            isExiting = true
            delay(550) // match exit animation duration
            onSplashComplete()
        }
    }

    // ─── container exit animation ────────────────────────────────────────────
    val containerScale by animateFloatAsState(
        targetValue = if (isExiting) 1.06f else 1f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "splashContainerScale",
    )
    val containerAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
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
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
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

            // ── 1. Logo ──────────────────────────────────────────────────────
            SplashLogo(visible = logoVisible)

            Spacer(modifier = Modifier.height(36.dp))

            // ── 2. Equalizer bars ────────────────────────────────────────────
            SplashEqualizerBars(visible = eqVisible)

            Spacer(modifier = Modifier.height(28.dp))

            // ── 3. App title ─────────────────────────────────────────────────
            val titleAlpha by animateFloatAsState(
                targetValue = if (titleVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 600),
                label = "titleAlpha",
            )
            val titleOffsetY by animateFloatAsState(
                targetValue = if (titleVisible) 0f else 28f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
                label = "titleOffsetY",
            )
            Text(
                text = "MusicPlayer",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .alpha(titleAlpha)
                    .offset(y = titleOffsetY.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── 4. Tagline ───────────────────────────────────────────────────
            val taglineAlpha by animateFloatAsState(
                targetValue = if (taglineVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 800),
                label = "taglineAlpha",
            )
            Text(
                text = "Feel every beat",
                style = MaterialTheme.typography.bodyMedium.copy(
                    letterSpacing = 1.8.sp,
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.alpha(taglineAlpha),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private sub-composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * App logo: a double-layered circle (pulsing glow ring behind a solid icon disc)
 * that springs in from zero scale.
 */
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
        animationSpec = tween(durationMillis = 350),
        label = "logoAlpha",
    )

    // Infinite pulse on the glow ring — only runs after the logo is visible
    val glowTransition = rememberInfiniteTransition(label = "logoGlow")
    val glowScale by glowTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )
    val glowAlpha by glowTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = FastOutSlowInEasing),
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
                .size(110.dp)
                .scale(if (visible) glowScale else 1f)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = if (visible) glowAlpha else 0f
                    )
                )
        )
        // Second diffuse ring for depth
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                )
        )
        // Solid icon disc
        Box(
            modifier = Modifier
                .size(72.dp)
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
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * Seven equalizer bars with staggered durations and start offsets so they pulse
 * organically rather than in lockstep. The bar heights follow a bell-curve
 * envelope (short on the edges, tallest in the centre) for a classic EQ look.
 *
 * @param visible Controls whether the bars appear (fades the whole row in).
 */
@Composable
private fun SplashEqualizerBars(visible: Boolean) {
    val rowAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "eqRowAlpha",
    )

    // Each bar: (animDurationMs, startOffsetMs, maxHeightDp)
    // Heights form a symmetric bell curve; durations & offsets are intentionally
    // irregular so no two bars sync up.
    data class BarSpec(val durationMs: Int, val offsetMs: Int, val maxHeightDp: Dp)
    val barSpecs = listOf(
        BarSpec(420,   0, 22.dp),
        BarSpec(560, 110, 34.dp),
        BarSpec(390, 200, 44.dp),
        BarSpec(620,  50, 48.dp),
        BarSpec(470, 160, 44.dp),
        BarSpec(510,  80, 34.dp),
        BarSpec(380, 130, 22.dp),
    )

    val infiniteTransition = rememberInfiniteTransition(label = "eqBars")
    val barFractions = barSpecs.mapIndexed { index, spec ->
        infiniteTransition.animateFloat(
            initialValue = 0.12f,
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
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .height(52.dp)
            .alpha(rowAlpha),
    ) {
        barSpecs.forEachIndexed { index, spec ->
            val fraction by barFractions[index]
            val barHeight = spec.maxHeightDp * fraction.coerceIn(0.08f, 1f)

            Box(
                modifier = Modifier
                    .width(8.dp)
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