package com.engfred.musicplayer.feature_player.utils

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

data class AlbumArtAnalysis(
    val contentColor: Color,       // computed text/icon color (black or white)
    val topRegionLuminance: Float  // average luminance of the top region (0..1)
)

private suspend fun loadBitmap(context: Context, albumArtUri: String?): Bitmap? {
    if (albumArtUri == null) return null
    return try {
        val request = ImageRequest.Builder(context)
            .data(albumArtUri)
            .allowHardware(false)
            .build()
        val drawable = context.imageLoader.execute(request).drawable
        drawable?.toBitmap()
    } catch (e: Exception) {
        null
    }
}

private fun rgbToLuminance(r: Int, g: Int, b: Int): Float {
    return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
}

private fun isNearWhite(r: Int, g: Int, b: Int, threshold: Int = 250): Boolean {
    return r >= threshold && g >= threshold && b >= threshold
}

private fun pickBestPaletteColor(palette: Palette): Int? {
    val candidates = listOf(
        palette.vibrantSwatch,
        palette.dominantSwatch,
        palette.mutedSwatch,
        palette.darkVibrantSwatch,
        palette.lightMutedSwatch,
        palette.darkMutedSwatch,
        palette.lightVibrantSwatch
    )
    for (s in candidates) {
        if (s != null) {
            val c = s.rgb
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (!isNearWhite(r, g, b)) return c
        }
    }
    return palette.dominantSwatch?.rgb
}

suspend fun analyzeAlbumArt(context: Context, albumArtUri: String?): AlbumArtAnalysis {
    val default = AlbumArtAnalysis(contentColor = Color.White, topRegionLuminance = 1f)
    val bitmap = withContext(Dispatchers.IO) { loadBitmap(context, albumArtUri) } ?: return default

    return withContext(Dispatchers.Default) {
        try {
            // Sample top region fraction (tunable)
            val sampleFraction = 0.12f
            val bw = max(1, bitmap.width)
            val bh = max(1, bitmap.height)
            val topH = max(1, (bh * sampleFraction).toInt())

            val stepX = max(1, bw / 100)
            val stepY = max(1, topH / 20)

            var count = 0
            var luminanceSum = 0f

            for (y in 0 until topH step stepY) {
                for (x in 0 until bw step stepX) {
                    val px = bitmap.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    luminanceSum += rgbToLuminance(r, g, b)
                    count++
                }
            }

            val avgTopLuminance = if (count > 0) luminanceSum / count.toFloat() else 1f

            // Decide text/icon color from top region if clear; otherwise, use palette heuristics
            when {
                avgTopLuminance >= 0.62f -> return@withContext AlbumArtAnalysis(contentColor = Color.Black, topRegionLuminance = avgTopLuminance)
                avgTopLuminance <= 0.38f -> return@withContext AlbumArtAnalysis(contentColor = Color.White, topRegionLuminance = avgTopLuminance)
            }

            // ambiguous -> use Palette swatches but avoid near-white swatches
            val palette = Palette.from(bitmap).generate()
            val best = pickBestPaletteColor(palette)
            if (best != null) {
                val r = (best shr 16) and 0xFF
                val g = (best shr 8) and 0xFF
                val b = best and 0xFF
                val lum = rgbToLuminance(r, g, b)
                val contentColor = if (lum > 0.5f) Color.Black else Color.White
                return@withContext AlbumArtAnalysis(contentColor = contentColor, topRegionLuminance = avgTopLuminance)
            }

            // Last-resort overall sample
            var overallSum = 0f
            var overallCount = 0
            val stepXO = max(1, bw / 50)
            val stepYO = max(1, bh / 50)
            for (y in 0 until bh step stepYO) {
                for (x in 0 until bw step stepXO) {
                    val px = bitmap.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    overallSum += rgbToLuminance(r, g, b)
                    overallCount++
                }
            }
            val overallAvg = if (overallCount > 0) overallSum / overallCount.toFloat() else 1f
            val fallbackColor = if (overallAvg > 0.5f) Color.Black else Color.White
            AlbumArtAnalysis(contentColor = fallbackColor, topRegionLuminance = avgTopLuminance)
        } catch (t: Throwable) {
            default
        }
    }
}

/** Compose wrapper for use in Composables. */
@Composable
fun getAlbumArtAnalysis(context: Context, albumArtUri: String?): AlbumArtAnalysis {
    val result by produceState(initialValue = AlbumArtAnalysis(Color.White, 1f), albumArtUri) {
        value = analyzeAlbumArt(context, albumArtUri)
    }
    return result
}

/** Backwards-compatible helper (same name as before) that returns only the content color. */
@Composable
fun getContentColorForAlbumArt(context: Context, albumArtUri: String?): Color {
    return getAlbumArtAnalysis(context, albumArtUri).contentColor
}
