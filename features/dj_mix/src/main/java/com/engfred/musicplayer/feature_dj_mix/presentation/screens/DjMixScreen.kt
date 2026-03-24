package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.feature_dj_mix.presentation.components.BpmAnalysisCard
import com.engfred.musicplayer.feature_dj_mix.presentation.components.ControlsCard
import com.engfred.musicplayer.feature_dj_mix.presentation.components.NowPlayingCard
import com.engfred.musicplayer.feature_dj_mix.presentation.components.SmartQueueItem
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixEvent
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixViewModel

/**
 * Root screen for the BPM-Aware DJ Auto-Mix feature.
 *
 * Layout:
 * - [TopAppBar]     — playlist name + back arrow.
 * - Analysis card   — progress bar while BPM worker is running.
 * - Now Playing     — current track, BPM badge, playback progress bar.
 * - Crossfade viz   — animated timeline showing the in-flight fade.
 * - Controls card   — crossfade duration slider, BPM tolerance slider, play/pause.
 * - Smart queue     — reordered songs with BPM badges; tap to jump.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjMixScreen(
    onNavigateBack: () -> Unit,
    viewModel: DjMixViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Initialize the CrossfadeEngine on the Main thread — the Composable's effect
    // runs on Main, which is exactly what ExoPlayer requires.
    LaunchedEffect(Unit) {
        if (uiState.smartQueue.isNotEmpty() && uiState.currentTrack == null) {
            viewModel.crossfadeEngine.startPlayback(uiState.smartQueue.first())
        }
    }

    // Auto-start once the smart queue is first populated
    LaunchedEffect(uiState.smartQueue) {
        if (uiState.smartQueue.isNotEmpty() && uiState.currentTrack == null && !uiState.isLoading) {
            viewModel.crossfadeEngine.startPlayback(uiState.smartQueue.first())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "DJ Mix",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.playlistName.isNotBlank()) {
                            Text(
                                text = uiState.playlistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                )
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── BPM Analysis Progress ────────────────────────────────────────
            if (uiState.isAnalyzing || uiState.analysisProgress < 1f) {
                item {
                    BpmAnalysisCard(
                        progress = uiState.analysisProgress,
                        analysedCount = (uiState.analysisProgress * uiState.totalSongs).toInt(),
                        totalCount = uiState.totalSongs
                    )
                }
            }

            // ── Now Playing ──────────────────────────────────────────────────
            uiState.currentTrack?.let { track ->
                item {
                    NowPlayingCard(
                        trackTitle = track.title,
                        trackArtist = track.artist ?: "Unknown Artist",
                        bpm = uiState.bpmCache[track.id]?.bpm,
                        positionMs = uiState.currentPositionMs,
                        durationMs = uiState.currentDurationMs,
                        isCrossfading = uiState.isCrossfading,
                        crossfadeProgress = uiState.crossfadeProgressFraction
                    )
                }
            }

            // ── Playback Controls ────────────────────────────────────────────
            item {
                ControlsCard(
                    isPlaying = uiState.isPlaying,
                    crossfadeDurationSec = uiState.settings.crossfadeDurationSec,
                    bpmTolerance = uiState.settings.bpmTolerance,
                    isRealMixMode = uiState.settings.isRealMixMode,
                    maxTrackDurationSec = uiState.settings.maxTrackDurationSec,
                    loopQueue = uiState.settings.loopQueue,
                    onPlayPause = { viewModel.onEvent(DjMixEvent.PlayPause) },
                    onCrossfadeDurationChanged = { sec ->
                        viewModel.onEvent(DjMixEvent.UpdateCrossfadeDuration(sec))
                    },
                    onBpmToleranceChanged = { tol ->
                        viewModel.onEvent(DjMixEvent.UpdateBpmTolerance(tol))
                    },
                    onToggleRealMixMode = { enabled ->
                        viewModel.onEvent(DjMixEvent.ToggleRealMixMode(enabled))
                    },
                    onMaxDurationChanged = { sec ->
                        viewModel.onEvent(DjMixEvent.UpdateMaxTrackDuration(sec))
                    },
                    onToggleLoopQueue = { enabled ->
                        viewModel.onEvent(DjMixEvent.ToggleLoopQueue(enabled))
                    }
                )
            }

            // ── Smart Queue Header ───────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Smart Queue  •  ${uiState.smartQueue.size} songs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ── Smart Queue Items ────────────────────────────────────────────
            itemsIndexed(
                items = uiState.smartQueue,
                key = { _, song -> song.id }
            ) { index, song ->
                SmartQueueItem(
                    position = index + 1,
                    song = song,
                    bpm = uiState.bpmCache[song.id]?.bpm,
                    isCurrent = song.id == uiState.currentTrack?.id,
                    onClick = { viewModel.onEvent(DjMixEvent.JumpToTrack(song)) }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}