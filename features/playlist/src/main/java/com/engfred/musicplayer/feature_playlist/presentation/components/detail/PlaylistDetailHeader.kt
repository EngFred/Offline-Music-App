package com.engfred.musicplayer.feature_playlist.presentation.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants as LottieLibConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.util.TextUtils
import com.engfred.musicplayer.feature_playlist.R
import com.engfred.musicplayer.feature_playlist.utils.findFirstAlbumArtUri
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
fun PlaylistDetailHeaderSection(
    playlist: Playlist?,
    isCompact: Boolean,
    isFavPlaylist: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val displayArt = playlist?.customArtUri ?: playlist?.findFirstAlbumArtUri()
        val playlistName = playlist?.name ?: "Unknown Playlist"
        val songCount = playlist?.songs?.size ?: 0

        val totalDurationMs = playlist?.songs?.fold(0L) { acc, song -> acc + song.duration } ?: 0L
        val formattedDuration = if (totalDurationMs > 0) {
            val totalMinutes = totalDurationMs / 60000
            if (totalMinutes > 60) {
                val hours = totalMinutes / 60
                val mins = totalMinutes % 60
                "${hours}h ${mins}m"
            } else {
                "${totalMinutes} min"
            }
        } else null

        val subtitleText = buildString {
            append(TextUtils.pluralize(songCount, "song"))
            if (formattedDuration != null) {
                append(" • ")
                append(formattedDuration)
            }
        }

        val imageSize = if (isCompact) 200.dp else 240.dp
        val titleStyle = if (isCompact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = if (isCompact) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.size(16.dp))

            if (isFavPlaylist && playlist?.customArtUri == null) {
                val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.love))
                if (!isCompact) Spacer(Modifier.size(24.dp))
                if (composition != null) {
                    LottieAnimation(
                        composition = composition,
                        iterations = LottieLibConstants.IterateForever,
                        speed = 1f,
                        modifier = Modifier
                            .graphicsLayer(alpha = 0.9f)
                            .clip(RoundedCornerShape(if (isCompact) 20.dp else 24.dp))
                            .background(Color.Transparent),
                        contentScale = ContentScale.FillBounds
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = "Favorites",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(imageSize)
                            .clip(RoundedCornerShape(if (isCompact) 20.dp else 24.dp))
                            .shadow(
                                elevation = if (isCompact) 12.dp else 16.dp,
                                shape = RoundedCornerShape(if (isCompact) 20.dp else 24.dp),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            } else {
                if (!isCompact) Spacer(Modifier.size(24.dp))
                CoilImage(
                    imageModel = { displayArt },
                    imageOptions = ImageOptions(
                        contentDescription = "Playlist Album Art",
                        contentScale = ContentScale.Crop
                    ),
                    modifier = Modifier
                        .size(imageSize)
                        .clip(RoundedCornerShape(if (isCompact) 20.dp else 24.dp))
                        .shadow(
                            elevation = if (isCompact) 12.dp else 16.dp,
                            shape = RoundedCornerShape(if (isCompact) 20.dp else 24.dp),
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF2C283E), Color(0xFF161426))
                            )
                        ),
                    failure = {
                        Box(
                            modifier = Modifier
                                .size(imageSize)
                                .clip(RoundedCornerShape(if (isCompact) 20.dp else 24.dp))
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF2C283E), Color(0xFF161426))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = "No Album Art",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(imageSize * 0.4f)
                            )
                        }
                    },
                    loading = {
                        Box(
                            modifier = Modifier
                                .size(imageSize)
                                .clip(RoundedCornerShape(if (isCompact) 20.dp else 24.dp))
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF2C283E), Color(0xFF161426))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = "Loading Album Art",
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(imageSize * 0.4f)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 16.dp else 24.dp))
            Text(
                text = playlistName,
                style = titleStyle.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}