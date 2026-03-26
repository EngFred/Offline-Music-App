package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.feature_dj_mix.presentation.components.BpmAnalysisSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.ControlsSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.NowPlayingSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.SmartQueueItem
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixEvent
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
                    // Logic to start foreground service
                }
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val queue    = uiState.smartQueue
        val fromIdx  = queue.indexOfFirst { it.id == from.key as Long }
        val toIdx    = queue.indexOfFirst { it.id == to.key as Long }
        if (fromIdx != -1 && toIdx != -1) {
            viewModel.onEvent(DjMixEvent.MoveTrack(fromIdx, toIdx))
        }
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A0D), Color(0xFF14141C), Color(0xFF0A0A0D))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text          = "DJ STUDIO",
                            style         = MaterialTheme.typography.titleMedium,
                            fontWeight    = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color         = Color.White
                        )
                        if (uiState.playlistName.isNotBlank()) {
                            Text(
                                text          = uiState.playlistName.uppercase(),
                                style         = MaterialTheme.typography.labelSmall,
                                color         = Color.White.copy(alpha = 0.5f),
                                maxLines      = 1,
                                overflow      = TextOverflow.Ellipsis,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor         = Color.Transparent,
                    scrolledContainerColor = Color(0xFF0A0A0D).copy(alpha = 0.95f)
                )
            )
        },
        floatingActionButton = {
            if (uiState.currentTrack != null && uiState.isPlaying) {
                FloatingActionButton(
                    onClick        = { viewModel.onEvent(DjMixEvent.MixNow) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor   = MaterialTheme.colorScheme.onPrimary,
                    shape          = RoundedCornerShape(percent = 50),
                    modifier       = Modifier.padding(bottom = 16.dp, end = 8.dp)
                ) {
                    Row(
                        modifier          = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, "Mix Now")
                        Spacer(Modifier.width(8.dp))
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
                state          = lazyListState,
                modifier       = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top    = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                if (uiState.isAnalyzing || uiState.analysisProgress < 1f ||
                    uiState.analysisFailedCount > 0) {
                    item(key = "analysis_section") {
                        BpmAnalysisSection(
                            progress      = uiState.analysisProgress,
                            analysedCount = (uiState.analysisProgress * uiState.totalSongs).toInt(),
                            totalCount    = uiState.totalSongs,
                            failedCount   = uiState.analysisFailedCount,
                            modifier      = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                uiState.currentTrack?.let { track ->
                    item(key = "now_playing") {
                        val bpmInfo = uiState.bpmCache[track.id]
                        NowPlayingSection(
                            trackTitle         = track.title,
                            trackArtist        = track.artist ?: "Unknown Artist",
                            bpm                = bpmInfo?.bpm?.takeIf { bpmInfo.analysisFailed != true },
                            positionMs         = uiState.currentPositionMs,
                            durationMs         = uiState.currentDurationMs,
                            isCrossfading      = uiState.isCrossfading,
                            crossfadeProgress  = uiState.crossfadeProgressFraction,
                            currentMixStrategy = uiState.currentMixStrategy,
                            albumArtUri        = track.albumArtUri,
                            waveform           = uiState.waveform,
                            isPlaying          = uiState.isPlaying,
                            timeToNextMixMs    = uiState.timeToNextMixMs,
                            onAbortCrossfade   = { viewModel.onEvent(DjMixEvent.AbortCrossfade) },
                            modifier           = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                item(key = "controls") {
                    ControlsSection(
                        isPlaying            = uiState.isPlaying,
                        crossfadeDurationSec = uiState.settings.crossfadeDurationSec,
                        bpmTolerance         = uiState.settings.bpmTolerance,
                        isRealMixMode        = uiState.settings.isRealMixMode,
                        maxTrackDurationSec  = uiState.settings.maxTrackDurationSec,
                        useManualMaxDuration = uiState.settings.useManualMaxDuration,
                        loopQueue            = uiState.settings.loopQueue,
                        canSkipBack          = uiState.canSkipBack,
                        onPlayPause          = { viewModel.onEvent(DjMixEvent.PlayPause) },
                        onSkipBack           = { viewModel.onEvent(DjMixEvent.SkipBack) },
                        onCrossfadeDurationChanged  = { viewModel.onEvent(DjMixEvent.UpdateCrossfadeDuration(it)) },
                        onBpmToleranceChanged       = { viewModel.onEvent(DjMixEvent.UpdateBpmTolerance(it)) },
                        onToggleRealMixMode         = { viewModel.onEvent(DjMixEvent.ToggleRealMixMode(it)) },
                        onToggleManualMaxDuration   = { viewModel.onEvent(DjMixEvent.ToggleManualMaxDuration(it)) },
                        onMaxDurationChanged        = { viewModel.onEvent(DjMixEvent.UpdateMaxTrackDuration(it)) },
                        onToggleLoopQueue           = { viewModel.onEvent(DjMixEvent.ToggleLoopQueue(it)) },
                        modifier             = Modifier.padding(horizontal = 24.dp)
                    )
                }

                item(key = "queue_header") {
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text          = "UP NEXT",
                            style         = MaterialTheme.typography.titleSmall,
                            fontWeight    = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color         = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.weight(1f))

                        IconButton(
                            onClick  = { viewModel.onEvent(DjMixEvent.SortByBpm) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = "Sort by BPM",
                                tint               = Color.White.copy(alpha = 0.6f),
                                modifier           = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick  = { viewModel.onEvent(DjMixEvent.ShuffleQueue) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Rounded.Shuffle,
                                contentDescription = "Shuffle queue",
                                tint               = if (uiState.isQueueUserOrdered)
                                    MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.6f),
                                modifier           = Modifier.size(18.dp)
                            )
                        }

                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = "${uiState.smartQueue.size} TRACKS",
                            style      = MaterialTheme.typography.labelMedium,
                            color      = Color.White.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                itemsIndexed(
                    items = uiState.smartQueue,
                    key   = { _, song -> song.id }
                ) { index, song ->
                    ReorderableItem(reorderableState, key = song.id) { isDragging ->
                        val elevation = if (isDragging) 8.dp else 0.dp
                        Surface(
                            shadowElevation = elevation,
                            color           = Color.Transparent
                        ) {
                            SmartQueueItem(
                                position            = index + 1,
                                song                = song,
                                bpm                 = uiState.bpmCache[song.id]?.bpm
                                    ?.takeIf { uiState.bpmCache[song.id]?.analysisFailed != true },
                                analysisFailed      = uiState.bpmCache[song.id]?.analysisFailed == true,
                                isCurrent           = song.id == uiState.currentTrack?.id,
                                // ── NEW: Pass isPlayed state ──
                                isPlayed            = song.id in uiState.playedTrackIds && song.id != uiState.currentTrack?.id,
                                onClick             = { viewModel.onEvent(DjMixEvent.JumpToTrack(song)) },
                                dragHandleModifier  = Modifier.draggableHandle()
                            )
                        }
                    }
                }
            }
        }
    }
}