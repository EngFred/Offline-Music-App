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
    modifier: Modifier = Modifier
) {

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Sizes
    val compactBaseSize = 340.dp
    val compactPausedSize = compactBaseSize * 0.7f
    val expandedBaseSize = 460.dp
    val fixedContainerSize = if (isLandscape) expandedBaseSize else compactBaseSize

    // We want to control the expansion state manually based on logic, not just raw 'isPlaying'
    var isExpanded by remember { mutableStateOf(isPlaying) }

    // We track the last song ID that was actually PLAYING
    var lastPlayingSongId by remember { mutableStateOf(currentSongId) }

    LaunchedEffect(isPlaying, currentSongId) {
        if (isPlaying) {
            // Case 1: Music is playing. Always expand.
            isExpanded = true
            lastPlayingSongId = currentSongId
        } else {
            // Case 2: Music paused/stopped.
            // Check: Did we change songs?
            if (currentSongId != lastPlayingSongId && currentSongId != null) {
                // If the ID changed, it's a SKIP (Buffering).
                // Keep it expanded to avoid the "shrink-bounce" glitch.
                isExpanded = true
                // Update the ID so if they pause *now*, it will shrink correctly
                lastPlayingSongId = currentSongId
            } else {
                // The ID is the same. This is a genuine USER PAUSE.
                // We add a tiny micro-delay just to smooth out frame-drops during aggressive skipping
                delay(50)
                isExpanded = false
            }
        }
    }

    // Determine target size based on our calculated 'isExpanded' state
    val targetSize: Dp = if (isLandscape.not()) {
        if (isExpanded) compactBaseSize else compactPausedSize
    } else compactPausedSize

    val defaultArtSize = if (isExpanded) 200.dp else 100.dp

    val animatedAlbumArtSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "etherealFlowAlbumArtSize"
    )

    Box(
        modifier = modifier.size(fixedContainerSize),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(animatedAlbumArtSize)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            CoilImage(
                imageModel = { albumArtUri },
                imageOptions = ImageOptions(
                    contentDescription = "Album Art",
                    contentScale = ContentScale.FillBounds
                ),
                modifier = Modifier.fillMaxSize(),
                failure = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "No Album Art",
                            modifier = Modifier.size(defaultArtSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                },
                loading = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            )
        }
    }
}