package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
import com.engfred.musicplayer.core.domain.model.DjMixPlaylistFilter
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.feature_dj_mix.presentation.components.MixStudioLauncherShimmer
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.MixStudioLauncherViewModel

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MixStudioLauncherScreen(
    onPlaylistSelected: (Long) -> Unit,
    viewModel: MixStudioLauncherViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        when {
            uiState.isLoading -> {
                // Show the perfectly matched shimmer layout instead of a spinner
                MixStudioLauncherShimmer(modifier = Modifier.fillMaxSize())
            }

            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            uiState.isEmpty -> {
                EmptyLauncherState(modifier = Modifier.fillMaxSize())
            }

            else -> {
                LauncherContent(
                    automaticPlaylists = uiState.automaticPlaylists,
                    userPlaylists = uiState.userPlaylists,
                    currentFilter = uiState.currentFilter,
                    onFilterSelected = viewModel::setFilter,
                    onPlaylistSelected = onPlaylistSelected,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LauncherContent(
    automaticPlaylists: List<Playlist>,
    userPlaylists: List<Playlist>,
    currentFilter: DjMixPlaylistFilter,
    onFilterSelected: (DjMixPlaylistFilter) -> Unit,
    onPlaylistSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Page Header ──
        item {
            Text(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                text = "SELECT A PLAYLIST TO MIX",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        // ── Professional Filter Chips ──
        item {
            FilterChipRow(
                currentFilter = currentFilter,
                onFilterSelected = onFilterSelected,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }

        if (automaticPlaylists.isNotEmpty() && (currentFilter == DjMixPlaylistFilter.ALL || currentFilter == DjMixPlaylistFilter.AUTOMATIC)) {
            item {
                SectionLabel(
                    text = "SYSTEM PLAYLISTS",
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            items(automaticPlaylists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onPlaylistSelected(playlist.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // ── User Playlists (Conditional) ──
        if (userPlaylists.isNotEmpty() && (currentFilter == DjMixPlaylistFilter.ALL || currentFilter == DjMixPlaylistFilter.USER)) {
            item {
                SectionLabel(
                    text = "CUSTOM PLAYLISTS",
                    modifier = Modifier.padding(
                        start = 24.dp, end = 24.dp,
                        top = if (automaticPlaylists.isNotEmpty() && currentFilter == DjMixPlaylistFilter.ALL) 24.dp else 16.dp,
                        bottom = 8.dp
                    )
                )
            }
            items(userPlaylists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onPlaylistSelected(playlist.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FilterChipRow(
    currentFilter: DjMixPlaylistFilter,
    onFilterSelected: (DjMixPlaylistFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CustomFilterChip(
                text = "All",
                isSelected = currentFilter == DjMixPlaylistFilter.ALL,
                onClick = { onFilterSelected(DjMixPlaylistFilter.ALL) }
            )
        }
        item {
            CustomFilterChip(
                text = "System",
                isSelected = currentFilter == DjMixPlaylistFilter.AUTOMATIC,
                onClick = { onFilterSelected(DjMixPlaylistFilter.AUTOMATIC) }
            )
        }
        item {
            CustomFilterChip(
                text = "Custom",
                isSelected = currentFilter == DjMixPlaylistFilter.USER,
                onClick = { onFilterSelected(DjMixPlaylistFilter.USER) }
            )
        }
    }
}

@Composable
private fun CustomFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(200), label = "chipBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200), label = "chipContent"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

@Composable
private fun PlaylistPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                // Using a subtle tonal surface color instead of a random gradient
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote, // The universal music note placeholder
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val artUri: Uri? = playlist.customArtUri ?: playlist.songs.firstOrNull()?.albumArtUri
    val hasValidImage = artUri != null && artUri.path?.isNotEmpty() == true

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(60.dp),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp
            ) {
                if (hasValidImage) {
                    // Use SubcomposeAsyncImage to allow Composable error states
                    coil.compose.SubcomposeAsyncImage(
                        model = artUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = {
                            PlaylistPlaceholder() // Now this will work!
                        },
                        loading = {
                            // Optional: Show placeholder while loading
                            PlaylistPlaceholder()
                        }
                    )
                } else {
                    PlaylistPlaceholder()
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.songs.size} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE600E6),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "MIX",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun EmptyLauncherState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "NO MIXABLE PLAYLISTS",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "The Mix Engine requires playlists to have more than 2 tracks. Add more songs to your playlists in the Library to start mixing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            textAlign = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun AutomaticPlaylistType?.toIcon(): ImageVector = when (this) {
    AutomaticPlaylistType.RECENTLY_ADDED -> Icons.Rounded.AccessTime
    AutomaticPlaylistType.MOST_PLAYED    -> Icons.Rounded.Star
    AutomaticPlaylistType.ARTIST         -> Icons.Rounded.Person
    AutomaticPlaylistType.MIX_OF_THE_DAY -> Icons.Rounded.AutoAwesome
    null                                  -> Icons.Rounded.MusicNote
}