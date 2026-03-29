package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
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
import com.engfred.musicplayer.feature_dj_mix.data.service.DjMixService
import com.engfred.musicplayer.feature_dj_mix.presentation.components.BpmAnalysisSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.ControlsSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.NowPlayingSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.SmartQueueItem
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixEvent
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixViewModel
import kotlinx.coroutines.launch

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjMixScreen(
    onNavigateBack: () -> Unit,
    viewModel: DjMixViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSettingsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                "START_DJ_SERVICE" -> {
                    val intent = Intent(context, DjMixService::class.java).apply {
                        action = DjMixService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Creates the deep, emotional background extraction feel using the current theme's primary color
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text          = "MIX STUDIO",
                            style         = MaterialTheme.typography.titleMedium,
                            fontWeight    = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color         = MaterialTheme.colorScheme.onBackground
                        )
                        if (uiState.playlistName.isNotBlank()) {
                            Text(
                                text          = uiState.playlistName.uppercase(),
                                style         = MaterialTheme.typography.labelSmall,
                                color         = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                maxLines      = 1,
                                overflow      = TextOverflow.Ellipsis,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Rounded.Settings, "DJ Settings", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor         = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                )
            )
        },
        floatingActionButtonPosition = FabPosition.Center, // Centers all FABs at the bottom
        floatingActionButton = {
            if (uiState.currentTrack != null) {
                // Currently Playing Controls
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Persistent Play/Pause FAB
                    FloatingActionButton(
                        onClick        = { viewModel.onEvent(DjMixEvent.PlayPause) },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape          = RoundedCornerShape(percent = 50)
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause"
                        )
                    }

                    // ── LOGIC FIX: Only show "MIX NOW" if a next track exists ──
                    if (uiState.isPlaying && uiState.nextTrack != null) {
                        FloatingActionButton(
                            onClick        = { viewModel.onEvent(DjMixEvent.MixNow) },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor   = MaterialTheme.colorScheme.onPrimary,
                            shape          = RoundedCornerShape(percent = 50)
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
                }
            } else if (uiState.smartQueue.isNotEmpty()) {
                // Initial "Start Mix" FAB when nothing is playing yet
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.onEvent(DjMixEvent.PlayPause)
                        // Scroll to the top when the mix starts
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(0)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor   = MaterialTheme.colorScheme.onPrimary,
                    shape          = RoundedCornerShape(percent = 50),
                    modifier       = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Start Mix")
                    Spacer(Modifier.width(8.dp))
                    Text("START MIX", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
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
                    bottom = 120.dp // Padding for FABs
                ),
                verticalArrangement = Arrangement.spacedBy(32.dp)
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
                            trackTitle        = track.title,
                            trackArtist       = track.artist ?: "Unknown Artist",
                            bpm               = bpmInfo?.bpm?.takeIf { bpmInfo.analysisFailed != true },
                            positionMs        = uiState.currentPositionMs,
                            durationMs        = uiState.currentDurationMs,
                            isCrossfading     = uiState.isCrossfading,
                            crossfadeProgress = uiState.crossfadeProgressFraction,
                            currentMixStrategy = uiState.currentMixStrategy,
                            albumArtUri       = track.albumArtUri,
                            waveform          = uiState.waveform,
                            isPlaying         = uiState.isPlaying,
                            timeToNextMixMs   = uiState.timeToNextMixMs,
                            nextTrack         = uiState.nextTrack,
                            customCueInMs     = bpmInfo?.customCueInMs,
                            customMixOutMs    = bpmInfo?.customMixOutMs,
                            onAbortCrossfade  = { viewModel.onEvent(DjMixEvent.AbortCrossfade) },
                            onSetCueIn        = { viewModel.onEvent(DjMixEvent.SetCustomCueIn) },
                            onSetMixOut       = { viewModel.onEvent(DjMixEvent.SetCustomMixOut) },
                            onClearCues       = { viewModel.onEvent(DjMixEvent.ClearCustomCues) },
                            modifier          = Modifier.padding(horizontal = 24.dp)
                        )
                    }
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

                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = "${uiState.smartQueue.size} TRACKS",
                            style      = MaterialTheme.typography.labelMedium,
                            color      = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                itemsIndexed(
                    items = uiState.smartQueue,
                    key   = { _, song -> song.id }
                ) { index, song ->
                    SmartQueueItem(
                        position           = index + 1,
                        song               = song,
                        bpm                = uiState.bpmCache[song.id]?.bpm
                            ?.takeIf { uiState.bpmCache[song.id]?.analysisFailed != true },
                        analysisFailed     = uiState.bpmCache[song.id]?.analysisFailed == true,
                        isCurrent          = song.id == uiState.currentTrack?.id,
                        isPlayed           = song.id in uiState.playedTrackIds && song.id != uiState.currentTrack?.id,
                        onClick            = {
                            viewModel.onEvent(DjMixEvent.JumpToTrack(song))
                            // Optional: Scroll to top when manually picking a song too!
                            coroutineScope.launch {
                                lazyListState.animateScrollToItem(0)
                            }
                        },
                        onRemove           = { viewModel.onEvent(DjMixEvent.RemoveTrack(song)) }
                    )
                }
            }
        }
    }

    // ── DJ Settings Bottom Sheet ──────────────────────────────────────────────
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "MIX SETTINGS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                ControlsSection(
                    crossfadeDurationSec = uiState.settings.crossfadeDurationSec,
                    bpmTolerance         = uiState.settings.bpmTolerance,
                    isRealMixMode        = uiState.settings.isRealMixMode,
                    maxTrackDurationSec  = uiState.settings.maxTrackDurationSec,
                    useManualMaxDuration = uiState.settings.useManualMaxDuration,
                    loopQueue            = uiState.settings.loopQueue,
                    onCrossfadeDurationChanged = { viewModel.onEvent(DjMixEvent.UpdateCrossfadeDuration(it)) },
                    onBpmToleranceChanged      = { viewModel.onEvent(DjMixEvent.UpdateBpmTolerance(it)) },
                    onToggleRealMixMode        = { viewModel.onEvent(DjMixEvent.ToggleRealMixMode(it)) },
                    onToggleManualMaxDuration  = { viewModel.onEvent(DjMixEvent.ToggleManualMaxDuration(it)) },
                    onMaxDurationChanged       = { viewModel.onEvent(DjMixEvent.UpdateMaxTrackDuration(it)) },
                    onToggleLoopQueue          = { viewModel.onEvent(DjMixEvent.ToggleLoopQueue(it)) },
                    autoSamplerEnabled         = uiState.settings.autoSamplerEnabled,
                    sampleVolume               = uiState.settings.sampleVolume,
                    onToggleAutoSampler        = { viewModel.onEvent(DjMixEvent.ToggleAutoSampler(it)) },
                    onSampleVolumeChanged      = { viewModel.onEvent(DjMixEvent.UpdateSampleVolume(it)) },
                )
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}