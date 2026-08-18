package com.engfred.musicplayer.feature_player.presentation.components

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay

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
    val screenHeightDp = configuration.screenHeightDp

    val compactBaseSize: Dp = if (screenHeightDp < 680) 240.dp else 280.dp
    val compactPausedSize: Dp = compactBaseSize * 0.82f
    val fixedContainerSize = if (isLandscape) 220.dp else compactBaseSize

    var isExpanded by remember { mutableStateOf(isPlaying) }
    var lastPlayingSongId by remember { mutableStateOf(currentSongId) }

    LaunchedEffect(isPlaying, currentSongId, isSeeking) {
        if (isSeeking) return@LaunchedEffect

        if (isPlaying) {
            isExpanded = true
            lastPlayingSongId = currentSongId
        } else {
            if (currentSongId != lastPlayingSongId && currentSongId != null) {
                isExpanded = true
                lastPlayingSongId = currentSongId
            } else {
                delay(50)
                isExpanded = false
            }
        }
    }

    val targetSize: Dp = if (!isLandscape) {
        if (isExpanded) compactBaseSize else compactPausedSize
    } else compactPausedSize

    val animatedAlbumArtSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "albumArtSize"
    )

    Box(
        modifier = modifier.size(fixedContainerSize),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(animatedAlbumArtSize)
                .shadow(
                    elevation = if (isExpanded) 16.dp else 6.dp,
                    shape = RoundedCornerShape(24.dp),
                    clip = false
                )
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF2C283E), Color(0xFF161426))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
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
                            modifier = Modifier.size(animatedAlbumArtSize * 0.4f),
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    },
                    loading = {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = "No Album Art",
                    modifier = Modifier.size(animatedAlbumArtSize * 0.4f),
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}