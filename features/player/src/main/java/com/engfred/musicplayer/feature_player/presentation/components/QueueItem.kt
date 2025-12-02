package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlin.random.Random

@Composable
fun QueueItem(
    audioFile: AudioFile,
    isCurrentlyPlaying: Boolean,
    onPlayClick: () -> Unit,
    onRemoveClick: () -> Unit,
    showAlbumArt: Boolean = true,
    isLastItem: Boolean = false,
    isPlaying: Boolean = false
) {
//    val backgroundColor = if (isCurrentlyPlaying) {
//        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
//    } else {
//        Color.Transparent
//    }

    val backgroundColor = Color.Transparent

    val contentColor = if (isCurrentlyPlaying) {
        MaterialTheme.colorScheme.primary
    } else {
        LocalContentColor.current
    }
    val artistColor = if (isCurrentlyPlaying) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        LocalContentColor.current.copy(alpha = 0.7f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPlayClick)
            .padding(start = 15.dp),
    ) {

        if (showAlbumArt) Spacer(Modifier.size(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (showAlbumArt)  {

                CoilImage(
                    imageModel = { audioFile.albumArtUri },
                    imageOptions = ImageOptions(
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    failure = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                contentDescription = "No Album Art",
                            )
                        }
                    },
                    loading = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                contentDescription = "No Album Art",
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = audioFile.title ?: "Unknown Title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = audioFile.artist ?: "Unknown Artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = artistColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrentlyPlaying) {
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .align(Alignment.CenterVertically),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    EqualizerBar(contentColor, isPlaying)
                    EqualizerBar(contentColor, isPlaying)
                    EqualizerBar(contentColor, isPlaying)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // Option to remove item from queue
            IconButton(onClick = onRemoveClick) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove from queue",
                    tint = LocalContentColor.current.copy(alpha = 0.6f)
                )
            }
        }
        if (showAlbumArt) Spacer(Modifier.size(6.dp))
        if (!isLastItem) {
            HorizontalDivider()
        }
    }
}

@Composable
private fun EqualizerBar(
    color: Color,
    isPlaying: Boolean
) {
    val barHeight = remember { Animatable(Random.nextFloat() * (16f - 2f) + 2f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                val newTarget = Random.nextFloat() * (16f - 2f) + 2f
                val duration = Random.nextInt(300, 601)
                barHeight.animateTo(newTarget, tween(duration))
            }
        }
    }
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(barHeight.value.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}