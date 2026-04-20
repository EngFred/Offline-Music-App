package com.engfred.musicplayer.feature_dj_mix.presentation.screens

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.feature_dj_mix.data.service.AutoMixService
import com.engfred.musicplayer.feature_dj_mix.presentation.components.AnalysisInProgressDialog
import com.engfred.musicplayer.feature_dj_mix.presentation.components.BpmAnalysisSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.ControlsSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.DualDeckSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.MixNowRow
import com.engfred.musicplayer.feature_dj_mix.presentation.components.NowPlayingSection
import com.engfred.musicplayer.feature_dj_mix.presentation.components.SmartQueueItem
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.MixStudioEvent
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.MixStudioViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixStudioScreen(
    onNavigateBack: () -> Unit,
    viewModel: MixStudioViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSettingsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                "START_DJ_SERVICE" -> {
                    val intent = Intent(context, AutoMixService::class.java).apply {
                        action = AutoMixService.ACTION_START
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
    val analysedCount = (uiState.analysisProgress * uiState.totalSongs).toInt()

    // Scroll behavior allows the transparent top bar to become solid when content scrolls under it
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background
        )
    )

    // ── Auto-scroll to top when a fresh mix session starts ──
    // We track the previous track ID to ensure we only scroll when going
    // from "No Track" to "Playing Track", so we don't annoy the user by
    // yanking their screen to the top on every subsequent song change.
    var previousTrackId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(uiState.currentTrack?.id) {
        val newTrackId = uiState.currentTrack?.id
        if (previousTrackId == null && newTrackId != null) {
            // Give the LazyColumn a split-second to insert the NowPlaying component into the tree
            delay(150)
            lazyListState.animateScrollToItem(0)
        }
        previousTrackId = newTrackId
    }

    // ── Precompute next-track BPM for the dual-deck section ─────────────────
    // Extracted here so the lambda is stable and doesn't cause unnecessary
    // recompositions inside the LazyColumn item.
    val nextTrackBpm = uiState.nextTrack?.id?.let { id ->
        uiState.bpmCache[id]?.bpm?.takeIf { uiState.bpmCache[id]?.analysisFailed != true }
    }

    if (isLandscape) {
        // ══════════════════════════════════════════════════════════════
        //  LANDSCAPE  — Split-panel layout
        // ══════════════════════════════════════════════════════════════
        Scaffold(
            containerColor = Color.Transparent
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
                    .padding(paddingValues)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {

                    // ── LEFT PANEL: Now Playing ───────────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f),
                                        Color.Transparent
                                    )
                                )
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text          = "MIX STUDIO",
                                    style         = MaterialTheme.typography.titleSmall,
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
                            // ── Deck layout toggle (landscape) ────────────────
                            IconButton(onClick = { viewModel.onEvent(MixStudioEvent.ToggleDeckLayout) }) {
                                Icon(
                                    imageVector        = if (uiState.isDualDeckMode)
                                        Icons.Rounded.ViewStream else Icons.Rounded.ViewColumn,
                                    contentDescription = if (uiState.isDualDeckMode)
                                        "Switch to single deck" else "Switch to dual deck",
                                    tint = if (uiState.isDualDeckMode)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onBackground
                                )
                            }
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = "DJ Settings",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }

                        // Scrollable now-playing content
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(
                                horizontal = 20.dp,
                                vertical   = 8.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (uiState.isAnalyzing || uiState.analysisProgress < 1f ||
                                uiState.analysisFailedCount > 0) {
                                item(key = "analysis_section") {
                                    BpmAnalysisSection(
                                        progress      = uiState.analysisProgress,
                                        analysedCount = analysedCount,
                                        totalCount    = uiState.totalSongs,
                                        failedCount   = uiState.analysisFailedCount,
                                        modifier      = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            uiState.currentTrack?.let { track ->
                                if (uiState.isDualDeckMode) {
                                    // ── DUAL DECK (landscape left panel) ─────
                                    item(key = "dual_deck") {
                                        DualDeckSection(
                                            currentTrack       = track,
                                            currentBpm         = uiState.bpmCache[track.id]?.bpm
                                                ?.takeIf { uiState.bpmCache[track.id]?.analysisFailed != true },
                                            positionMs         = uiState.currentPositionMs,
                                            durationMs         = uiState.currentDurationMs,
                                            isPlaying          = uiState.isPlaying,
                                            waveform           = uiState.waveform,
                                            currentAlbumArtUri = track.albumArtUri,
                                            nextTrack          = uiState.nextTrack,
                                            nextBpm            = nextTrackBpm,
                                            nextAlbumArtUri    = uiState.nextTrack?.albumArtUri,
                                            isCrossfading      = uiState.isCrossfading,
                                            crossfadeProgress  = uiState.crossfadeProgressFraction,
                                            currentMixStrategy = uiState.currentMixStrategy,
                                            timeToNextMixMs    = uiState.timeToNextMixMs,
                                            modifier           = Modifier.fillMaxWidth()
                                        )
                                    }
                                } else {
                                    // ── SINGLE DECK (landscape left panel) ───
                                    item(key = "now_playing") {
                                        NowPlayingSection(
                                            trackTitle         = track.title,
                                            trackArtist        = track.artist ?: "Unknown Artist",
                                            bpm                = uiState.bpmCache[track.id]?.bpm
                                                ?.takeIf { uiState.bpmCache[track.id]?.analysisFailed != true },
                                            positionMs         = uiState.currentPositionMs,
                                            durationMs         = uiState.currentDurationMs,
                                            isCrossfading      = uiState.isCrossfading,
                                            crossfadeProgress  = uiState.crossfadeProgressFraction,
                                            currentMixStrategy = uiState.currentMixStrategy,
                                            albumArtUri        = track.albumArtUri,
                                            waveform           = uiState.waveform,
                                            isPlaying          = uiState.isPlaying,
                                            timeToNextMixMs    = uiState.timeToNextMixMs,
                                            nextTrack          = uiState.nextTrack,
                                            modifier           = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            // ── Mix Now button — only visible when playing in Real Mix Mode ────────────
                            if (uiState.currentTrack != null && uiState.settings.isRealMixMode) {
                                item(key = "mix_now_row") {
                                    MixNowRow(
                                        isCrossfading = uiState.isCrossfading,
                                        onMixNow      = { viewModel.onEvent(MixStudioEvent.MixStudioNow) },
                                        modifier      = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // ── Play/Pause button pinned at bottom of left panel ──
                        val fabLabel = when {
                            uiState.currentTrack != null             -> null
                            uiState.pendingAutoStartAfterAnalysis    -> "WAITING…"
                            uiState.smartQueue.isNotEmpty()          -> "START MIX"
                            else                                     -> null
                        }
                        if (fabLabel != null || uiState.currentTrack != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.currentTrack != null) {
                                    FloatingActionButton(
                                        onClick        = { viewModel.onEvent(MixStudioEvent.PlayPause) },
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
                                        shape          = RoundedCornerShape(percent = 50)
                                    ) {
                                        Icon(
                                            imageVector        = if (uiState.isPlaying)
                                                Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = "Play/Pause"
                                        )
                                    }
                                } else {
                                    ExtendedFloatingActionButton(
                                        onClick = { viewModel.onEvent(MixStudioEvent.PlayPause) },
                                        containerColor = if (uiState.pendingAutoStartAfterAnalysis)
                                            MaterialTheme.colorScheme.secondaryContainer
                                        else
                                            MaterialTheme.colorScheme.primary,
                                        contentColor = if (uiState.pendingAutoStartAfterAnalysis)
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        else
                                            MaterialTheme.colorScheme.onPrimary,
                                        shape = RoundedCornerShape(percent = 50),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            fabLabel ?: "",
                                            fontWeight    = FontWeight.Black,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Divider between panels
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                            )
                    )

                    // ── RIGHT PANEL: Queue ────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight()
                    ) {
                        // Queue header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
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
                            Text(
                                text       = "${uiState.smartQueue.size} TRACKS",
                                style      = MaterialTheme.typography.labelMedium,
                                color      = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                        )

                        LazyColumn(
                            state          = lazyListState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            )
                        ) {
                            itemsIndexed(
                                items = uiState.smartQueue,
                                key   = { _, song -> song.id }
                            ) { index, song ->
                                SmartQueueItem(
                                    position       = index + 1,
                                    song           = song,
                                    bpm            = uiState.bpmCache[song.id]?.bpm
                                        ?.takeIf { uiState.bpmCache[song.id]?.analysisFailed != true },
                                    analysisFailed = uiState.bpmCache[song.id]?.analysisFailed == true,
                                    isCurrent      = song.id == uiState.currentTrack?.id,
                                    isPlayed       = song.id in uiState.playedTrackIds &&
                                            song.id != uiState.currentTrack?.id,
                                )
                            }
                        }
                    }
                }
            }
        }

    } else {
        // ══════════════════════════════════════════════════════════════
        //  PORTRAIT  — Original single-column scroll layout
        // ══════════════════════════════════════════════════════════════
        Scaffold(
            // FIX: Tie the Scaffold's nested scroll to the TopAppBar so it knows when to change colors
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                // FIX: Use TopAppBar natively without the Box wrapper, enabling native status bar insets
                TopAppBar(
                    title = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier            = Modifier.fillMaxWidth()
                        ) {
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
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        // ── Deck layout toggle ────────────────────────────────
                        // ViewColumn  = two columns → tap to enter dual-deck mode
                        // ViewStream  = single stream → tap to return to single deck
                        IconButton(
                            onClick = { viewModel.onEvent(MixStudioEvent.ToggleDeckLayout) }
                        ) {
                            Icon(
                                imageVector        = if (uiState.isDualDeckMode)
                                    Icons.Rounded.ViewStream else Icons.Rounded.ViewColumn,
                                contentDescription = if (uiState.isDualDeckMode)
                                    "Switch to single deck" else "Switch to dual deck",
                                tint = if (uiState.isDualDeckMode)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = "DJ Settings",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    // FIX: Ensure status bar insets are respected natively
                    windowInsets = WindowInsets.statusBars,
                    // FIX: Make TopAppBar solid when scrolling so the vinyl doesn't peek through the text
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor         = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                    )
                )
            },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                if (uiState.currentTrack != null) {
                    FloatingActionButton(
                        onClick        = { viewModel.onEvent(MixStudioEvent.PlayPause) },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape          = RoundedCornerShape(percent = 50),
                        modifier       = Modifier.navigationBarsPadding()
                    ) {
                        Icon(
                            imageVector        = if (uiState.isPlaying) Icons.Rounded.Pause
                            else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause"
                        )
                    }
                } else if (uiState.smartQueue.isNotEmpty()) {
                    val fabLabel = if (uiState.pendingAutoStartAfterAnalysis)
                        "WAITING FOR ANALYSIS…"
                    else
                        "START MIX"

                    ExtendedFloatingActionButton(
                        onClick = { viewModel.onEvent(MixStudioEvent.PlayPause) },
                        containerColor = if (uiState.pendingAutoStartAfterAnalysis)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.primary,
                        contentColor = if (uiState.pendingAutoStartAfterAnalysis)
                            MaterialTheme.colorScheme.onSecondaryContainer
                        else
                            MaterialTheme.colorScheme.onPrimary,
                        shape    = RoundedCornerShape(percent = 50),
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .navigationBarsPadding()
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(fabLabel, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
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
                        bottom = 120.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    if (uiState.isAnalyzing || uiState.analysisProgress < 1f ||
                        uiState.analysisFailedCount > 0) {
                        item(key = "analysis_section") {
                            BpmAnalysisSection(
                                progress      = uiState.analysisProgress,
                                analysedCount = analysedCount,
                                totalCount    = uiState.totalSongs,
                                failedCount   = uiState.analysisFailedCount,
                                modifier      = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }

                    uiState.currentTrack?.let { track ->
                        if (uiState.isDualDeckMode) {
                            // ── DUAL DECK layout ──────────────────────────────
                            item(key = "dual_deck") {
                                DualDeckSection(
                                    currentTrack       = track,
                                    currentBpm         = uiState.bpmCache[track.id]?.bpm
                                        ?.takeIf { uiState.bpmCache[track.id]?.analysisFailed != true },
                                    positionMs         = uiState.currentPositionMs,
                                    durationMs         = uiState.currentDurationMs,
                                    isPlaying          = uiState.isPlaying,
                                    waveform           = uiState.waveform,
                                    currentAlbumArtUri = track.albumArtUri,
                                    nextTrack          = uiState.nextTrack,
                                    nextBpm            = nextTrackBpm,
                                    nextAlbumArtUri    = uiState.nextTrack?.albumArtUri,
                                    isCrossfading      = uiState.isCrossfading,
                                    crossfadeProgress  = uiState.crossfadeProgressFraction,
                                    currentMixStrategy = uiState.currentMixStrategy,
                                    timeToNextMixMs    = uiState.timeToNextMixMs,
                                    modifier           = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        } else {
                            // ── SINGLE DECK layout (default) ──────────────────
                            item(key = "now_playing") {
                                NowPlayingSection(
                                    trackTitle         = track.title,
                                    trackArtist        = track.artist ?: "Unknown Artist",
                                    bpm                = uiState.bpmCache[track.id]?.bpm
                                        ?.takeIf { uiState.bpmCache[track.id]?.analysisFailed != true },
                                    positionMs         = uiState.currentPositionMs,
                                    durationMs         = uiState.currentDurationMs,
                                    isCrossfading      = uiState.isCrossfading,
                                    crossfadeProgress  = uiState.crossfadeProgressFraction,
                                    currentMixStrategy = uiState.currentMixStrategy,
                                    albumArtUri        = track.albumArtUri,
                                    waveform           = uiState.waveform,
                                    isPlaying          = uiState.isPlaying,
                                    timeToNextMixMs    = uiState.timeToNextMixMs,
                                    nextTrack          = uiState.nextTrack,
                                    modifier           = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }
                    }

                    // ── Mix Now button — only visible when playing in Real Mix Mode ────────────
                    if (uiState.currentTrack != null && uiState.settings.isRealMixMode) {
                        item(key = "mix_now_row") {
                            MixNowRow(
                                isCrossfading = uiState.isCrossfading,
                                onMixNow      = { viewModel.onEvent(MixStudioEvent.MixStudioNow) },
                                modifier      = Modifier.padding(horizontal = 24.dp)
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
                            position       = index + 1,
                            song           = song,
                            bpm            = uiState.bpmCache[song.id]?.bpm
                                ?.takeIf { uiState.bpmCache[song.id]?.analysisFailed != true },
                            analysisFailed = uiState.bpmCache[song.id]?.analysisFailed == true,
                            isCurrent      = song.id == uiState.currentTrack?.id,
                            isPlayed       = song.id in uiState.playedTrackIds &&
                                    song.id != uiState.currentTrack?.id,
                        )
                    }
                }
            }
        }
    }

    // ── Analysis-in-progress confirmation dialog (shared by both orientations) ─
    if (uiState.showAnalysisDialog) {
        AnalysisInProgressDialog(
            analysedCount = analysedCount,
            totalCount    = uiState.totalSongs,
            progress      = uiState.analysisProgress,
            onDismiss     = { viewModel.onEvent(MixStudioEvent.DismissAnalysisDialog) },
            onWait        = { viewModel.onEvent(MixStudioEvent.WaitAndAutoStart) },
            onStartNow    = { viewModel.onEvent(MixStudioEvent.StartAnywayDespiteAnalysis) }
        )
    }

    // ── Settings bottom sheet (shared by both orientations) ──────────────────
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState       = sheetState,
            containerColor   = MaterialTheme.colorScheme.surface,
            dragHandle       = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text          = "MIX SETTINGS",
                    style         = MaterialTheme.typography.titleMedium,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color         = MaterialTheme.colorScheme.primary,
                    modifier      = Modifier.padding(bottom = 24.dp)
                )
                ControlsSection(
                    isRealMixMode           = uiState.settings.isRealMixMode,
                    onToggleRealMixMode     = { viewModel.onEvent(MixStudioEvent.ToggleRealMixStudioMode(it)) },
                    autoSamplerEnabled      = uiState.settings.autoSamplerEnabled,
                    sampleVolume            = uiState.settings.sampleVolume,
                    onToggleAutoSampler     = { viewModel.onEvent(MixStudioEvent.ToggleAutoSampler(it)) },
                    onSampleVolumeChanged   = { viewModel.onEvent(MixStudioEvent.UpdateSampleVolume(it)) },
                    cuePointOffsetSec       = uiState.settings.cuePointOffsetSec,
                    onCuePointOffsetChanged = { viewModel.onEvent(MixStudioEvent.UpdateCuePointOffset(it)) },
                    crossfadeDurationSec       = uiState.settings.crossfadeDurationSec,
                    onCrossfadeDurationChanged = { viewModel.onEvent(MixStudioEvent.UpdateCrossfadeDuration(it)) },
                )
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}