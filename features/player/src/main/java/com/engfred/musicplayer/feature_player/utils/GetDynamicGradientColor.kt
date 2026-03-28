package com.engfred.musicplayer.feature_player.utils

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts up to four perceptually distinct colors from [uri] using Palette.
 *
 * The returned list is used by [AnimatedBlobBackground] to drive the animated
 * blob colors. Each color is mildly darkened (~15 %) to sit well on a dark
 * canvas while keeping vibrance.  If fewer than four distinct swatches are
 * found the list is padded with deep-space defaults.
 *
 * Swatch priority order:
 *  DarkVibrant → Vibrant → LightVibrant → Muted → DarkMuted → LightMuted → Dominant
 *
 * A color is skipped if it is perceptually too close (colorDistance < 0.05)
 * to a color already in the selection.
 */
suspend fun getDynamicGradientColors(context: Context, uri: String?): List<Color> {
    val defaultColors = listOf(
        Color(0xFF1A0A2E),
        Color(0xFF0A142E),
        Color(0xFF0A2A1A),
        Color(0xFF2E1A0A),
    )

    if (uri == null) return defaultColors

    val loader = context.imageLoader
    val request = ImageRequest.Builder(context)
        .data(uri)
        .allowHardware(false)
        .build()

    return withContext(Dispatchers.IO) {
        try {
            val result  = loader.execute(request)
            val bitmap  = (result.drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext defaultColors

            val palette = Palette.from(bitmap).generate()

            // All available swatches in priority order — vibrant first
            val candidates: List<Palette.Swatch?> = listOf(
                palette.darkVibrantSwatch,
                palette.vibrantSwatch,
                palette.lightVibrantSwatch,
                palette.mutedSwatch,
                palette.darkMutedSwatch,
                palette.lightMutedSwatch,
                palette.dominantSwatch,
            )

            val selected = mutableListOf<Color>()

            for (swatch in candidates) {
                if (swatch == null) continue
                if (selected.size >= 4) break

                val candidate = Color(swatch.rgb)

                // Skip if too perceptually similar to any already-selected color
                val tooClose = selected.any { existing ->
                    colorDistance(existing, candidate) < 0.05f
                }
                if (!tooClose) {
                    // Mild darkening (15 %) — keeps vibrancy while working on a dark canvas
                    selected.add(candidate.darken(0.15f))
                }
            }

            if (selected.isEmpty()) return@withContext defaultColors

            // Pad to 4 if we extracted fewer unique swatches
            while (selected.size < 4) selected.add(selected[0])

            selected
        } catch (e: Exception) {
            defaultColors
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

/** Perceptual-ish RGB distance in the 0..3 range (squared). */
private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.red   - b.red
    val dg = a.green - b.green
    val db = a.blue  - b.blue
    return dr * dr + dg * dg + db * db
}

/** Darken toward black using linear interpolation. [fraction] 0 = no change, 1 = black. */
private fun Color.darken(fraction: Float): Color =
    lerp(this, Color.Black, fraction.coerceIn(0f, 1f))