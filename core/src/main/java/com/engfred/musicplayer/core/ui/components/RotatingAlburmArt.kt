package com.engfred.musicplayer.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.util.rememberAlbumRotationDegrees
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
fun RotatingAlbumArt(
    imageModel: Any?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    isRotating: Boolean = false,
    durationMillis: Int = 4000,
    trackId: Any? = null, // unique id for track so helper can reset when id changes
    easeOutOnPause: Boolean = false //true to slow to stop
) {
    val rotationDegrees = rememberAlbumRotationDegrees(
        isRotating = isRotating,
        durationMillis = durationMillis,
        trackId = trackId,
        easeOutOnPause = easeOutOnPause
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .graphicsLayer { rotationZ = rotationDegrees }
        ) {
            CoilImage(
                imageModel = { imageModel },
                imageOptions = ImageOptions(
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop
                ),
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "Loading album art",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .align(Alignment.Center)
                        )
                    }
                },
                failure = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "No album art",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            )
        }
    }
}
