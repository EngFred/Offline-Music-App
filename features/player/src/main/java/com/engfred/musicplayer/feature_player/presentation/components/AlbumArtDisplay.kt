package com.engfred.musicplayer.feature_player.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
fun AlbumArtDisplay(
    albumArtUri: Any?,
    isPlaying: Boolean,
    currentSongId: Long?,
    isSeeking: Boolean = false,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Box(
            modifier = modifier.size(220.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp), clip = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                ArtworkContent(albumArtUri = albumArtUri)
            }
        }
    } else {
        // Portrait: Full-width square with small edge padding and subtle corners matching the reference design
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(12.dp),
                        clip = false
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                ArtworkContent(albumArtUri = albumArtUri)
            }
        }
    }
}

@Composable
private fun ArtworkContent(albumArtUri: Any?) {
    if (albumArtUri != null && albumArtUri.toString().isNotEmpty()) {
        CoilImage(
            imageModel = { albumArtUri },
            imageOptions = ImageOptions(
                contentDescription = "Album Art",
                contentScale = ContentScale.Crop
            ),
            modifier = Modifier.fillMaxSize(),
            failure = {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = "No Album Art",
                    modifier = Modifier.size(80.dp),
                    tint = Color.White.copy(alpha = 0.7f)
                )
            },
            loading = {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                )
            }
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = "No Album Art",
            modifier = Modifier.size(80.dp),
            tint = Color.White.copy(alpha = 0.7f)
        )
    }
}