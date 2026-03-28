package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Animated lava-lamp style background driven by album art colors.
 *
 * Four large radial gradient "blobs" drift independently across the canvas
 * using prime-ish animation durations so they never synchronise — producing
 * continuous, organic, non-repeating motion.
 *
 * Rendering layers (bottom → top):
 *  1. Near-black base                        — universal dark canvas
 *  2. Four semi-transparent color blobs       — album art colors, soft radial gradient
 *  3. Radial vignette                         — darkens edges, adds cinematic depth
 *  4. Top gradient fade                       — keeps status bar / top bar legible
 *  5. Bottom gradient fade                    — keeps playback controls legible
 *
 * @param colors  List of 1–4 [Color]s extracted from the current album art.
 *                Fewer than 4 are padded with deep-space defaults.
 */
@Composable
fun AnimatedBlobBackground(
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "blob_bg")

    // ── Blob 0 — roams the upper half ──────────────────────────────────────
    val b0x by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 13_700, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b0x"
    )
    val b0y by transition.animateFloat(
        initialValue = 0.1f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 17_300, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b0y"
    )

    // ── Blob 1 — sweeps right → left across full height ────────────────────
    val b1x by transition.animateFloat(
        initialValue = 1f, targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 11_100, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b1x"
    )
    val b1y by transition.animateFloat(
        initialValue = 0f, targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 15_600, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b1y"
    )

    // ── Blob 2 — rises from bottom-left corner ─────────────────────────────
    val b2x by transition.animateFloat(
        initialValue = 0.05f, targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 16_200, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b2x"
    )
    val b2y by transition.animateFloat(
        initialValue = 0.90f, targetValue = 0.20f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 12_800, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b2y"
    )

    // ── Blob 3 — accent blob, wanders center-right ─────────────────────────
    val b3x by transition.animateFloat(
        initialValue = 0.90f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 19_100, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b3x"
    )
    val b3y by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 14_400, easing = LinearEasing), RepeatMode.Reverse
        ), label = "b3y"
    )

    // Ensure exactly 4 colors; pad with deep-space hues that look great as defaults
    val blobColors = remember(colors) {
        val base = colors.take(4).toMutableList()
        val fallbacks = listOf(
            Color(0xFF1A0A2E),   // deep violet
            Color(0xFF0A142E),   // deep navy
            Color(0xFF0A2A1A),   // deep forest
            Color(0xFF2E1A0A),   // deep ember
        )
        while (base.size < 4) base.add(fallbacks[base.size])
        base
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Base radius — large enough so each blob fills most of the screen
        val r = maxOf(w, h)

        // ── 1. Base ───────────────────────────────────────────────────────
        drawRect(Color(0xFF06060E))

        // ── 2. Color blobs — 3-stop radial gradient for richer softness ───
        // Blob 0
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to blobColors[0].copy(alpha = 0.85f),
                    0.45f to blobColors[0].copy(alpha = 0.38f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(w * b0x, h * b0y),
                radius = r * 0.92f
            )
        )

        // Blob 1
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to blobColors[1].copy(alpha = 0.78f),
                    0.50f to blobColors[1].copy(alpha = 0.30f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(w * b1x, h * b1y),
                radius = r * 0.85f
            )
        )

        // Blob 2
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to blobColors[2].copy(alpha = 0.72f),
                    0.50f to blobColors[2].copy(alpha = 0.26f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(w * b2x, h * b2y),
                radius = r * 0.80f
            )
        )

        // Blob 3 — smaller accent, punchy alpha at centre
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to blobColors[3].copy(alpha = 0.65f),
                    0.40f to blobColors[3].copy(alpha = 0.22f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(w * b3x, h * b3y),
                radius = r * 0.62f
            )
        )

        // ── 3. Radial vignette — darkens edges, creates cinematic depth ───
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.62f to Color(0x55000000),
                    1.00f to Color(0xCC000000),
                ),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = r * 0.78f
            )
        )

        // ── 4. Top fade — status bar / TopBar legibility ──────────────────
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xAA000000), Color.Transparent),
                startY = 0f,
                endY = h * 0.18f
            )
        )

        // ── 5. Bottom fade — playback controls legibility ─────────────────
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0xBB000000)),
                startY = h * 0.72f,
                endY = h
            )
        )
    }
}