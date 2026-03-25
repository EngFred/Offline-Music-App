package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjMixScreen(
    onNavigateBack: () -> Unit,
    viewModel: DjMixViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Collect one-shot events from the ViewModel (e.g. start the foreground service).
    // No permission requests here — the synthetic waveform needs none.
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                "START_DJ_SERVICE" -> {
                    val intent = android.content.Intent(
                        context,
                        DjMixService::class.java
                    ).apply { action = DjMixService.ACTION_START }
                    ContextCompat.startForegroundService(context, intent)
                }
            }
        }
    }

    // Premium dark gradient background
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F0F13),
            Color(0xFF1A1A24),
            Color(0xFF0F0F13)
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "DJ STUDIO",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = Color.White
                        )
                        if (uiState.playlistName.isNotBlank()) {
                            Text(
                                text = uiState.playlistName.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color(0xFF0F0F13).copy(alpha = 0.9f)
                )
            )
        },
        floatingActionButton = {
            if (uiState.currentTrack != null && uiState.isPlaying) {
                FloatingActionButton(
                    onClick = { viewModel.onEvent(DjMixEvent.MixNow) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "Mix Now"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("MIX NOW", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Analysis progress card — visible while WorkManager is running
                if (uiState.isAnalyzing || uiState.analysisProgress < 1f) {
                    item {
                        BpmAnalysisCard(
                            progress      = uiState.analysisProgress,
                            analysedCount = (uiState.analysisProgress * uiState.totalSongs).toInt(),
                            totalCount    = uiState.totalSongs
                        )
                    }
                }

                // Hero: Now Playing card with synthetic beat-grid waveform
                uiState.currentTrack?.let { track ->
                    item {
                        val bpmInfo = uiState.bpmCache[track.id]
                        NowPlayingCard(
                            trackTitle       = track.title,
                            trackArtist      = track.artist ?: "Unknown Artist",
                            bpm              = bpmInfo?.bpm,
                            positionMs       = uiState.currentPositionMs,
                            durationMs       = uiState.currentDurationMs,
                            isCrossfading    = uiState.isCrossfading,
                            crossfadeProgress = uiState.crossfadeProgressFraction,
                            albumArtUri      = track.albumArtUri,
                            waveform         = uiState.waveform,
                            isPlaying        = uiState.isPlaying
                        )
                    }
                }

                // Controls deck
                item {
                    ControlsCard(
                        isPlaying             = uiState.isPlaying,
                        crossfadeDurationSec  = uiState.settings.crossfadeDurationSec,
                        bpmTolerance          = uiState.settings.bpmTolerance,
                        isRealMixMode         = uiState.settings.isRealMixMode,
                        maxTrackDurationSec   = uiState.settings.maxTrackDurationSec,
                        useManualMaxDuration  = uiState.settings.useManualMaxDuration,
                        loopQueue             = uiState.settings.loopQueue,
                        onPlayPause           = { viewModel.onEvent(DjMixEvent.PlayPause) },
                        onCrossfadeDurationChanged = { sec ->
                            viewModel.onEvent(DjMixEvent.UpdateCrossfadeDuration(sec))
                        },
                        onBpmToleranceChanged = { tol ->
                            viewModel.onEvent(DjMixEvent.UpdateBpmTolerance(tol))
                        },
                        onToggleRealMixMode   = { enabled ->
                            viewModel.onEvent(DjMixEvent.ToggleRealMixMode(enabled))
                        },
                        onToggleManualMaxDuration = { enabled ->
                            viewModel.onEvent(DjMixEvent.ToggleManualMaxDuration(enabled))
                        },
                        onMaxDurationChanged  = { sec ->
                            viewModel.onEvent(DjMixEvent.UpdateMaxTrackDuration(sec))
                        },
                        onToggleLoopQueue     = { enabled ->
                            viewModel.onEvent(DjMixEvent.ToggleLoopQueue(enabled))
                        }
                    )
                }

                // Smart queue header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Equalizer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "SMART QUEUE",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${uiState.smartQueue.size} TRACKS",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }

                // Smart queue items
                itemsIndexed(
                    items = uiState.smartQueue,
                    key   = { _, song -> song.id }
                ) { index, song ->
                    SmartQueueItem(
                        position = index + 1,
                        song     = song,
                        bpm      = uiState.bpmCache[song.id]?.bpm,
                        isCurrent = song.id == uiState.currentTrack?.id,
                        onClick  = { viewModel.onEvent(DjMixEvent.JumpToTrack(song)) }
                    )
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

// Avoids a fully-qualified reference inside the LaunchedEffect above
private typealias DjMixService = com.engfred.musicplayer.feature_dj_mix.data.service.DjMixService