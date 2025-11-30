package com.engfred.musicplayer.feature_playlist.presentation.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants as LottieLibConstants  // Alias to avoid conflicts
import com.airbnb.lottie.compose.rememberLottieComposition
import com.engfred.musicplayer.feature_playlist.R
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.feature_playlist.utils.findFirstAlbumArtUri
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/**
 * Composable for the header section of the Playlist Detail screen.
 * Displays playlist art, title, and options like adding songs or renaming.
 *
 * The top bar functionality (back arrow and more options) has been moved to a separate
 * `PlaylistDetailTopBar` composable which is now placed outside the scrollable list.
 * The `onNavigateBack` and `onMoreMenuExpandedChange` callbacks are no longer used here.
 */
@Composable
fun PlaylistDetailHeaderSection(
    playlist: Playlist?,
    isCompact: Boolean,
    isFavPlaylist: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Playlist Header (Image and Title)
        val firstSongAlbumArt = playlist?.findFirstAlbumArtUri()
        val playlistName = playlist?.name ?: "Unknown Playlist"

        val imageSize = if (isCompact) 200.dp else 240.dp
        val titleStyle = if (isCompact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = if (isCompact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.size(20.dp))

            if (isFavPlaylist) {
                val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.love))
                if (isCompact.not()) Spacer(Modifier.size(24.dp))
                if (composition != null) {
                    LottieAnimation(
                        composition = composition,
                        iterations = LottieLibConstants.IterateForever,
                        speed = 1f,  // Smooth playback speed
                        modifier = Modifier
                            .graphicsLayer(alpha = 0.9f)  // Subtle fade for integration
                            .clip(RoundedCornerShape(if (isCompact) 16.dp else 20.dp))
                            .background(Color.Transparent),
                        contentScale = ContentScale.FillBounds
                    )
                } else {
                    // Fallback: Static heart icon if Lottie fails to load
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = "Favorites",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(imageSize)
                            .clip(RoundedCornerShape(if (isCompact) 16.dp else 20.dp))
                            .shadow(
                                elevation = if (isCompact) 12.dp else 20.dp,
                                shape = RoundedCornerShape(if (isCompact) 16.dp else 20.dp),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            } else {
                // non-favorite: use Coil to load first song album art (with loading/failure)
                if (isCompact.not()) Spacer(Modifier.size(34.dp))
                CoilImage(
                    imageModel = { firstSongAlbumArt },
                    imageOptions = ImageOptions(
                        contentDescription = "Playlist Album Art",
                        contentScale = ContentScale.FillBounds
                    ),
                    modifier = Modifier
                        .size(imageSize)
                        .clip(RoundedCornerShape(if (isCompact) 16.dp else 20.dp))
                        .shadow(
                            elevation = if (isCompact) 12.dp else 20.dp,
                            shape = RoundedCornerShape(if (isCompact) 16.dp else 20.dp),
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    failure = {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "No Album Art",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(imageSize)
                                .clip(RoundedCornerShape(if (isCompact) 16.dp else 20.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    },
                    loading = {
                        Box(
                            modifier = Modifier
                                .size(imageSize)
                                .clip(RoundedCornerShape(if (isCompact) 16.dp else 20.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = "Loading Album Art",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(imageSize * 0.6f)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 13.dp else 32.dp))
            Text(
                text = playlistName,
                style = titleStyle.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}