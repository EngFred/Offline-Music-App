package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
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

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun BpmAnalysisCard(
    progress: Float,
    analysedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 600),
        label = "bpm_analysis_progress"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Analysing BPM…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "$analysedCount / $totalCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(),
                strokeCap = StrokeCap.Round,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f)
            )
        }
    }
}

@Composable
private fun NowPlayingCard(
    trackTitle: String,
    trackArtist: String,
    bpm: Float?,
    positionMs: Long,
    durationMs: Long,
    isCrossfading: Boolean,
    crossfadeProgress: Float,
    modifier: Modifier = Modifier
) {
    val playbackProgress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animatedPlayback by animateFloatAsState(
        targetValue = playbackProgress,
        animationSpec = tween(durationMillis = 300),
        label = "playback_progress"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vinyl disc icon
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trackTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = trackArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // BPM Badge
                bpm?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "${it.toInt()} BPM",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Playback progress
            LinearProgressIndicator(
                progress = { animatedPlayback },
                modifier = Modifier.fillMaxWidth(),
                strokeCap = StrokeCap.Round,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
            )

            // Crossfade timeline
            if (isCrossfading) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Crossfading",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    val animatedCrossfade by animateFloatAsState(
                        targetValue = crossfadeProgress,
                        label = "crossfade_progress"
                    )
                    LinearProgressIndicator(
                        progress = { animatedCrossfade },
                        modifier = Modifier.weight(1f),
                        strokeCap = StrokeCap.Round,
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                    )
                }
            }

            // Time display
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
                Text(
                    text = formatMs(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ControlsCard(
    isPlaying: Boolean,
    crossfadeDurationSec: Int,
    bpmTolerance: Float,
    isRealMixMode: Boolean,
    maxTrackDurationSec: Int,
    onPlayPause: () -> Unit,
    onCrossfadeDurationChanged: (Int) -> Unit,
    onBpmToleranceChanged: (Float) -> Unit,
    onToggleRealMixMode: (Boolean) -> Unit,
    onMaxDurationChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Play / Pause button
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(64.dp)
                    .clickable(onClick = onPlayPause)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Real Mix Mode Toggle ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Real Mix Mode",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Mix tracks before they finish",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isRealMixMode,
                    onCheckedChange = onToggleRealMixMode
                )
            }

            if (isRealMixMode) {
                Spacer(modifier = Modifier.height(12.dp))
                SliderWithLabel(
                    label = "Max Song Playtime",
                    valueLabel = "${maxTrackDurationSec / 60}m ${maxTrackDurationSec % 60}s",
                    value = maxTrackDurationSec.toFloat(),
                    valueRange = 60f..300f, // 1 min to 5 mins
                    steps = 24, // 10 second increments
                    onValueChange = { onMaxDurationChanged(it.toInt()) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))

            // Crossfade duration slider
            SliderWithLabel(
                label = "Crossfade Length",
                valueLabel = "${crossfadeDurationSec}s",
                value = crossfadeDurationSec.toFloat(),
                valueRange = 2f..12f,
                steps = 9,
                onValueChange = { onCrossfadeDurationChanged(it.toInt()) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // BPM tolerance slider
            SliderWithLabel(
                label = "BPM Tolerance",
                valueLabel = "±${bpmTolerance.toInt()} BPM",
                value = bpmTolerance,
                valueRange = 5f..20f,
                steps = 14,
                onValueChange = onBpmToleranceChanged
            )
        }
    }
}

@Composable
private fun SliderWithLabel(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun SmartQueueItem(
    position: Int,
    song: com.engfred.musicplayer.core.domain.model.AudioFile,
    bpm: Float?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isCurrent)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Position number
        Text(
            text = "$position",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.width(20.dp)
        )

        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist ?: "Unknown Artist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // BPM badge (or placeholder)
        if (bpm != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isCurrent)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "${bpm.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = if (isCurrent)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}