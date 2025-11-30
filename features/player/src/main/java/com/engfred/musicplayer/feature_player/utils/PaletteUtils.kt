package com.engfred.musicplayer.feature_player.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a list of unique [Color]s from an image Uri using Palette.
 * If no colors are extracted, returns the provided [fallback] color.
 */
suspend fun extractPaletteColors(
    context: Context,
    albumArtUri: Uri,
    fallback: Color
): List<Color> = withContext(Dispatchers.IO) {
    try {
        val loader = ImageLoader.Builder(context).build()
        val request = ImageRequest.Builder(context)
            .data(albumArtUri)
            .allowHardware(false)
            .size(coil.size.Size.ORIGINAL)
            .build()

        val result = loader.execute(request)
        if (result is SuccessResult) {
            val drawable = result.drawable
            val bitmap: Bitmap? = (drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val palette = Palette.from(bitmap).generate()
                val swatchInts = listOfNotNull(
                    palette.vibrantSwatch?.rgb,
                    palette.darkVibrantSwatch?.rgb,
                    palette.lightVibrantSwatch?.rgb,
                    palette.mutedSwatch?.rgb,
                    palette.darkMutedSwatch?.rgb,
                    palette.lightMutedSwatch?.rgb,
                    palette.dominantSwatch?.rgb
                )
                // Deduplicate while preserving order
                val uniqueInts = LinkedHashSet<Int>()
                swatchInts.forEach { uniqueInts.add(it) }
                val colors = uniqueInts.map { Color(it) }
                return@withContext colors.ifEmpty { listOf(fallback) }
            }
        }
        listOf(fallback)
    } catch (t: Throwable) {
        listOf(fallback)
    }
}
