package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
import com.engfred.musicplayer.core.domain.model.DjMixPlaylistFilter
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixLauncherViewModel

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DjMixLauncherScreen(
    onPlaylistSelected: (Long) -> Unit,
    viewModel: DjMixLauncherViewModel = hiltViewModel()
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
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

/** * A sleek, horizontally scrollable row of custom pill-shaped chips
 * to act as the primary filter for the screen.
 */
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
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val artUri: Uri? = playlist.customArtUri ?: playlist.songs.firstOrNull()?.albumArtUri

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (artUri != null) {
                    AsyncImage(
                        model = artUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Icon(
                    imageVector = playlist.type.toIcon(),
                    contentDescription = null,
                    tint = if (artUri != null)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0f)
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${playlist.songs.size} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledTonalButton(
                onClick = onClick,
                shape = RoundedCornerShape(percent = 50),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "MIX",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    style = MaterialTheme.typography.labelMedium
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

private fun AutomaticPlaylistType?.toIcon(): ImageVector = when (this) {
    AutomaticPlaylistType.RECENTLY_ADDED -> Icons.Rounded.AccessTime
    AutomaticPlaylistType.MOST_PLAYED    -> Icons.Rounded.Star
    AutomaticPlaylistType.ARTIST         -> Icons.Rounded.Person
    AutomaticPlaylistType.MIX_OF_THE_DAY -> Icons.Rounded.AutoAwesome
    null                                  -> Icons.Rounded.MusicNote
}