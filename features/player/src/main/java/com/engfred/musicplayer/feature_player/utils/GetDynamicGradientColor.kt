package com.engfred.musicplayer.feature_player.utils

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun getDynamicGradientColors(context: Context, uri: String?): List<Color> {
    // Default dark gradient colors
    val defaultColors = listOf(Color(0xFF1E1E1E), Color(0xFF333333))

    if (uri == null) return defaultColors

    val loader = context.imageLoader
    val request = ImageRequest.Builder(context)
        .data(uri)
        .allowHardware(false)
        .build()

    return withContext(Dispatchers.IO) {
        try {
            val result = loader.execute(request)
            val bitmapDrawable = result.drawable as? BitmapDrawable
            val bitmap = bitmapDrawable?.bitmap

            if (bitmap != null) {
                val palette = Palette.from(bitmap).generate()

                val vibrant = palette.vibrantSwatch
                val darkVibrant = palette.darkVibrantSwatch
                val muted = palette.mutedSwatch
                val darkMuted = palette.darkMutedSwatch
                val dominant = palette.dominantSwatch

                // Prefer darker swatches first so extracted colors are naturally darker
                val primarySwatchInt = (darkVibrant ?: darkMuted ?: vibrant ?: muted ?: dominant)?.rgb
                val secondarySwatchInt = when {
                    // try to get a different swatch for variety
                    primarySwatchInt == null -> (darkMuted ?: muted ?: vibrant ?: dominant)?.rgb
                    else -> (darkMuted?.rgb ?: muted?.rgb ?: vibrant?.rgb ?: dominant?.rgb)
                }

                // If no swatches found, fallback to defaults
                if (primarySwatchInt == null) {
                    return@withContext defaultColors
                }

                // Construct Compose colors
                val primary = Color(primarySwatchInt)
                val secondary = secondarySwatchInt?.let { Color(it) } ?: primary

                // Aggressively darken the extracted colors to avoid too-bright gradients.
                // Using lerp with black yields perceptually better results than scaling channels.
                // primaryDark: slightly less dark than secondaryDark so gradient has depth
                val primaryDark = primary.darken(0.28f).copy(alpha = 1f)
                val secondaryDark = secondary.darken(0.42f).copy(alpha = 1f)

                // Ensure two distinct colors; if they are too close, nudge secondary further
                val finalSecondary = if (colorDistance(primaryDark, secondaryDark) < 0.02f) {
                    secondaryDark.darken(0.08f)
                } else secondaryDark

                // Return sorted by luminance (dark -> lighter) so verticalGradient draws smoothly
                listOf(primaryDark, finalSecondary).sortedBy { it.luminance() }
            } else {
//                Log.w("getDynamicGradientColors", "Bitmap could not be extracted from URI: $uri")
                defaultColors
            }
        } catch (e: Exception) {
//            Log.e("getDynamicGradientColors", "Error generating palette from album art for URI: $uri", e)
            defaultColors
        }
    }
}

/** Utility: perceptual-ish distance between two colors (based on RGB floats). */
private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return (dr * dr + dg * dg + db * db)
}

/** Darken using lerp towards Color.Black for nicer visuals. fraction: 0..1 */
private fun Color.darken(fraction: Float): Color {
    return lerp(this, Color.Black, fraction.coerceIn(0f, 1f))
}