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
import com.engfred.musicplayer.feature_dj_mix.presentation.components.BpmAnalysisSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.ControlsSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.NowPlayingSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.SmartQueueItem
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixEvent
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixViewModel

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjMixScreen(
    onNavigateBack: () -> Unit,
    viewModel: DjMixViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

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

    // A deeper, more immersive background since there are no cards to break it up
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0A0A0D),
            Color(0xFF14141C),
            Color(0xFF0A0A0D)
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
                    scrolledContainerColor = Color(0xFF0A0A0D).copy(alpha = 0.95f)
                )
            )
        },
        floatingActionButton = {
            if (uiState.currentTrack != null && uiState.isPlaying) {
                FloatingActionButton(
                    onClick = { viewModel.onEvent(DjMixEvent.MixNow) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(percent = 50), // Fully rounded pill for pro feel
                    modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "Mix Now"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("MIX NOW", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = 120.dp // Leave room for FAB
                ),
                verticalArrangement = Arrangement.spacedBy(36.dp) // Increased spacing since cards are gone
            ) {
                // Analysis progress
                if (uiState.isAnalyzing || uiState.analysisProgress < 1f) {
                    item {
                        BpmAnalysisSection(
                            progress = uiState.analysisProgress,
                            analysedCount = (uiState.analysisProgress * uiState.totalSongs).toInt(),
                            totalCount = uiState.totalSongs,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                // Hero: Now Playing
                uiState.currentTrack?.let { track ->
                    item {
                        val bpmInfo = uiState.bpmCache[track.id]
                        NowPlayingSection(
                            trackTitle = track.title,
                            trackArtist = track.artist ?: "Unknown Artist",
                            bpm = bpmInfo?.bpm,
                            positionMs = uiState.currentPositionMs,
                            durationMs = uiState.currentDurationMs,
                            isCrossfading = uiState.isCrossfading,
                            crossfadeProgress = uiState.crossfadeProgressFraction,
                            currentMixStrategy = uiState.currentMixStrategy,
                            albumArtUri = track.albumArtUri,
                            waveform = uiState.waveform,
                            isPlaying = uiState.isPlaying,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                // Controls deck
                item {
                    ControlsSection(
                        isPlaying = uiState.isPlaying,
                        crossfadeDurationSec = uiState.settings.crossfadeDurationSec,
                        bpmTolerance = uiState.settings.bpmTolerance,
                        isRealMixMode = uiState.settings.isRealMixMode,
                        maxTrackDurationSec = uiState.settings.maxTrackDurationSec,
                        useManualMaxDuration = uiState.settings.useManualMaxDuration,
                        loopQueue = uiState.settings.loopQueue,
                        onPlayPause = { viewModel.onEvent(DjMixEvent.PlayPause) },
                        onCrossfadeDurationChanged = { sec -> viewModel.onEvent(DjMixEvent.UpdateCrossfadeDuration(sec)) },
                        onBpmToleranceChanged = { tol -> viewModel.onEvent(DjMixEvent.UpdateBpmTolerance(tol)) },
                        onToggleRealMixMode = { enabled -> viewModel.onEvent(DjMixEvent.ToggleRealMixMode(enabled)) },
                        onToggleManualMaxDuration = { enabled -> viewModel.onEvent(DjMixEvent.ToggleManualMaxDuration(enabled)) },
                        onMaxDurationChanged = { sec -> viewModel.onEvent(DjMixEvent.UpdateMaxTrackDuration(sec)) },
                        onToggleLoopQueue = { enabled -> viewModel.onEvent(DjMixEvent.ToggleLoopQueue(enabled)) },
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                // Smart queue header (integrated into flow)
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "UP NEXT",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "${uiState.smartQueue.size} TRACKS",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Smart queue items (No horizontal padding here so they bleed edge-to-edge)
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
            }
        }
    }
}

private typealias DjMixService = com.engfred.musicplayer.feature_dj_mix.data.service.DjMixService