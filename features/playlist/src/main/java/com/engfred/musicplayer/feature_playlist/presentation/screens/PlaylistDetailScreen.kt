package com.engfred.musicplayer.feature_playlist.presentation.screens

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
import com.engfred.musicplayer.core.ui.components.AddSongToPlaylistDialog
import com.engfred.musicplayer.core.ui.components.AudioFileItem
import com.engfred.musicplayer.core.ui.components.ConfirmationDialog
import com.engfred.musicplayer.core.ui.components.ErrorIndicator
import com.engfred.musicplayer.core.ui.components.InfoIndicator
import com.engfred.musicplayer.core.ui.components.MiniPlayer
import com.engfred.musicplayer.core.ui.components.ShimmerAudioFileItem
import com.engfred.musicplayer.core.util.TextUtils.pluralize
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.AddSongsBottomSheet
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.DjMixButton
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.PlaylistActionButtons
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.PlaylistDetailHeaderSection
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.PlaylistDetailTopBar
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.PlaylistEmptyState
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.PlaylistSongs
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.PlaylistSongsHeader
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.RenamePlaylistDialog
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.ShimmerPlaylistActionButtons
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.ShimmerPlaylistDetailHeaderSection
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.ShimmerPlaylistSongsHeader
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail.PlaylistDetailEvent
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail.PlaylistDetailViewModel
import com.engfred.musicplayer.feature_playlist.utils.findFirstAlbumArtUri
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    onEditInfo: (AudioFile) -> Unit,
    onTrimAudio: (AudioFile) -> Unit,
    stopAfterCurrent: Boolean,
    onToggleStopAfterCurrent: () -> Unit,
    playbackPositionMs: Long,
    totalDurationMs: Long,
    // ── DJ Mix entry point ────────────────────────────────────────────────────
    // Null means the caller does not support DJ Mix navigation (e.g. a test host).
    onDjMixClick: ((Long) -> Unit)? = null,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val mainLazyListState = rememberLazyListState()
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    var moreMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    var showAddSongsBottomSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showAddSongsBottomSheet) {
        AddSongsBottomSheet(
            onDismissRequest = { showAddSongsBottomSheet = false },
            sheetState = sheetState,
            allAudioFiles = uiState.allAudioFiles,
            currentPlaylistSongs = uiState.playlist?.songs ?: emptyList(),
            onSongsSelected = {
                it.forEach { song -> viewModel.onEvent(PlaylistDetailEvent.AddSong(song)) }
            }
        )
    }

    val thresholdPx = with(density) { 48.dp.toPx().toInt() }
    val scrolledPastHeader by remember {
        derivedStateOf {
            val state = if (!isLandscape) mainLazyListState else rightListState
            state.firstVisibleItemIndex > 0 ||
                    (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset > thresholdPx)
        }
    }

    // ReadOnly = automatic playlists that are NOT "Favorites".
    // User playlists and Favorites allow DJ Mix.
    val isReadOnlyPlaylist = uiState.playlist?.isAutomatic == true &&
            !uiState.playlist?.name.equals("Favorites", ignoreCase = true)

    // DJ Mix button is shown for ANY playlist with songs (automatic or manual)
    // as long as the caller has wired up onDjMixClick.
    val showDjMixButton = onDjMixClick != null
            && uiState.playlist?.songs?.isNotEmpty() == true

    val isSelectionMode = uiState.selectedSongs.isNotEmpty() && !isReadOnlyPlaylist
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = isSelectionMode) {
        viewModel.onEvent(PlaylistDetailEvent.DeselectAll)
    }

    val batchSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allowBatchDismiss by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            if (uiState.currentPlayingAudioFile != null) {
                MiniPlayer(
                    onClick = onNavigateToNowPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    onPlayPause = { viewModel.onEvent(PlaylistDetailEvent.PlayPause) },
                    onPlayNext = { viewModel.onEvent(PlaylistDetailEvent.PlayNext) },
                    onPlayPrev = { viewModel.onEvent(PlaylistDetailEvent.PlayPrev) },
                    isPlaying = uiState.isPlaying,
                    playingAudioFile = uiState.currentPlayingAudioFile,
                    stopAfterCurrent = stopAfterCurrent,
                    onToggleStopAfterCurrent = onToggleStopAfterCurrent,
                    playbackPositionMs = playbackPositionMs,
                    totalDurationMs = totalDurationMs
                )
            }
        },
    ) { paddingValues ->
        val mainContentModifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                )
            )
            .padding(paddingValues)

        if (isSelectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.onEvent(PlaylistDetailEvent.DeselectAll) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${uiState.selectedSongs.size} selected",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Row {
                        val allSelected = uiState.selectedSongs.size == uiState.sortedSongs.size
                        IconButton(onClick = {
                            if (allSelected) viewModel.onEvent(PlaylistDetailEvent.DeselectAll)
                            else viewModel.onEvent(PlaylistDetailEvent.SelectAll)
                        }) {
                            Icon(
                                if (allSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                contentDescription = "Select all"
                            )
                        }
                        IconButton(onClick = { viewModel.onEvent(PlaylistDetailEvent.ShowBatchRemoveConfirmation) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove selected")
                        }
                    }
                }
            }
        } else {
            PlaylistDetailTopBar(
                playlistName = uiState.playlist?.name,
                playlistArtUri = uiState.playlist?.findFirstAlbumArtUri(),
                scrolledPastHeader = scrolledPastHeader,
                onNavigateBack = {
                    if (isSelectionMode) viewModel.onEvent(PlaylistDetailEvent.DeselectAll)
                    else onNavigateBack()
                },
                onMoreMenuExpandedChange = { moreMenuExpanded = it },
                isAutomaticPlaylist = isReadOnlyPlaylist,
                onAddSongsClick = { showAddSongsBottomSheet = true },
                onRenamePlaylistClick = { viewModel.onEvent(PlaylistDetailEvent.ShowRenameDialog) },
                moreMenuExpanded = moreMenuExpanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .zIndex(2f),
                isEditable = !uiState.playlist?.name.equals("Favorites", ignoreCase = true)
            )
        }

        // ── Portrait layout ───────────────────────────────────────────────────
        if (!isLandscape) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = mainContentModifier.padding(horizontal = 8.dp),
                    state = mainLazyListState,
                    contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (uiState.isLoading) {
                        item {
                            ShimmerPlaylistDetailHeaderSection(
                                isCompact = true,
                                modifier = Modifier.padding(top = 38.dp)
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item {
                            Column(Modifier.fillMaxWidth()) {
                                ShimmerPlaylistActionButtons(isCompact = true)
                                Spacer(modifier = Modifier.height(24.dp))
                                ShimmerPlaylistSongsHeader()
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        items(10) {
                            ShimmerAudioFileItem()
                        }
                    } else {
                        item {
                            PlaylistDetailHeaderSection(
                                playlist = uiState.playlist,
                                isCompact = true,
                                modifier = Modifier.padding(top = 38.dp),
                                isFavPlaylist = uiState.playlist?.name.equals("Favorites", ignoreCase = true)
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item {
                            when {
                                uiState.error != null -> ErrorIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    message = uiState.error!!
                                )
                                uiState.playlist == null -> InfoIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    message = "Playlist not found or could not be loaded.",
                                    icon = Icons.Outlined.LibraryMusic
                                )
                                else -> {
                                    Column(Modifier.fillMaxWidth()) {
                                        PlaylistActionButtons(
                                            onPlayAllClick = {
                                                uiState.playlist?.songs?.let { songs ->
                                                    if (songs.isNotEmpty()) viewModel.onEvent(PlaylistDetailEvent.PlayAll)
                                                    else Toast.makeText(context, "Playlist is empty, cannot play.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onShuffleAllClick = {
                                                if (uiState.playlist?.songs?.isNotEmpty() == true) viewModel.onEvent(PlaylistDetailEvent.ShuffleAll)
                                                else Toast.makeText(context, "Playlist is empty, cannot shuffle play.", Toast.LENGTH_SHORT).show()
                                            },
                                            isCompact = true
                                        )

                                        // ── DJ Mix button (portrait) ──────────────
                                        if (showDjMixButton) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            DjMixButton(
                                                onClick = {
                                                    uiState.playlist?.id?.let { id -> onDjMixClick!!(id) }
                                                },
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(24.dp))
                                        PlaylistSongsHeader(
                                            songCount = uiState.playlist?.songs?.size ?: 0,
                                            currentSortOrder = uiState.currentSortOrder,
                                            onSortOrderChange = { viewModel.onEvent(PlaylistDetailEvent.SetSortOrder(it)) },
                                            sortMenuExpanded = sortMenuExpanded,
                                            onSortMenuExpandedChange = { sortMenuExpanded = it },
                                            isTopSongs = uiState.playlist?.type == AutomaticPlaylistType.MOST_PLAYED
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                        }

                        if (uiState.error == null && uiState.playlist != null && uiState.playlist?.songs.isNullOrEmpty()) {
                            item {
                                PlaylistEmptyState(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    playlistType = uiState.playlist?.type
                                )
                            }
                        } else if (uiState.error == null && uiState.playlist != null && !uiState.playlist?.songs.isNullOrEmpty()) {
                            itemsIndexed(
                                items = uiState.sortedSongs,
                                key = { _, audioFile -> audioFile.id }
                            ) { _, audioFile ->
                                val isSelected = uiState.selectedSongs.contains(audioFile)
                                AudioFileItem(
                                    audioFile = audioFile,
                                    isCurrentPlayingAudio = (audioFile.id == uiState.currentPlayingAudioFile?.id),
                                    onRemoveOrDelete = { song -> viewModel.onEvent(PlaylistDetailEvent.ShowRemoveSongConfirmation(song)) },
                                    modifier = Modifier.animateItem(),
                                    isAudioPlaying = uiState.isPlaying,
                                    onAddToPlaylist = { viewModel.onEvent(PlaylistDetailEvent.ShowPlaylistsDialog(it)) },
                                    onPlayNext = { viewModel.onEvent(PlaylistDetailEvent.SetPlayNext(it)) },
                                    isFromAutomaticPlaylist = isReadOnlyPlaylist,
                                    playCount = uiState.playlist?.playCounts?.get(audioFile.id),
                                    onEditInfo = onEditInfo,
                                    onTrimAudio = onTrimAudio,
                                    isSelectionMode = isSelectionMode,
                                    onSetAsPlaylistCover = { selectedSong ->
                                        viewModel.onEvent(PlaylistDetailEvent.SetPlaylistCover(selectedSong))
                                    },
                                    isSelected = isSelected,
                                    onToggleSelect = { viewModel.onEvent(PlaylistDetailEvent.ToggleSelection(audioFile)) },
                                    onItemTap = {
                                        if (isSelectionMode && !isReadOnlyPlaylist) viewModel.onEvent(PlaylistDetailEvent.ToggleSelection(audioFile))
                                        else viewModel.onEvent(PlaylistDetailEvent.PlayAudio(audioFile))
                                    },
                                    onItemLongPress = {
                                        if (!isSelectionMode && !isReadOnlyPlaylist) {
                                            viewModel.onEvent(PlaylistDetailEvent.ToggleSelection(audioFile))
                                        } else {
                                            Toast.makeText(context, "Cannot select songs from this playlist.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── Landscape layout ──────────────────────────────────────────────────
        } else {
            Row(modifier = mainContentModifier.padding(start = 30.dp, end = 8.dp)) {
                LazyColumn(
                    state = leftListState,
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                        .padding(end = 24.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.isLoading) {
                        item {
                            ShimmerPlaylistDetailHeaderSection(
                                isCompact = false,
                                modifier = Modifier.padding(top = 0.dp)
                            )
                        }
                        item {
                            ShimmerPlaylistActionButtons(isCompact = false)
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    } else {
                        item {
                            PlaylistDetailHeaderSection(
                                playlist = uiState.playlist,
                                isCompact = false,
                                modifier = Modifier.padding(top = 0.dp),
                                isFavPlaylist = uiState.playlist?.name.equals("Favorites", ignoreCase = true)
                            )
                        }
                        item {
                            PlaylistActionButtons(
                                onPlayAllClick = { if (uiState.playlist?.songs?.isNotEmpty() == true) viewModel.onEvent(PlaylistDetailEvent.PlayAll) },
                                onShuffleAllClick = { if (uiState.playlist?.songs?.isNotEmpty() == true) viewModel.onEvent(PlaylistDetailEvent.ShuffleAll) },
                                isCompact = false
                            )
                        }
                        // ── DJ Mix button (landscape) ─────────────────────────────
                        if (showDjMixButton) {
                            item {
                                DjMixButton(
                                    onClick = {
                                        uiState.playlist?.id?.let { id -> onDjMixClick!!(id) }
                                    }
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.18f))
                        .clip(MaterialTheme.shapes.medium)
                        .padding(0.dp)
                ) {
                    if (uiState.isLoading) {
                        Column(Modifier.fillMaxSize()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            ShimmerPlaylistSongsHeader()
                            Spacer(modifier = Modifier.height(16.dp))
                            repeat(10) {
                                ShimmerAudioFileItem()
                            }
                        }
                    } else {
                        when {
                            uiState.error != null -> ErrorIndicator(modifier = Modifier.fillMaxSize(), message = uiState.error!!)
                            uiState.playlist == null -> InfoIndicator(modifier = Modifier.fillMaxSize(), message = "Playlist not found or could not be loaded.", icon = Icons.Outlined.LibraryMusic)
                            else -> {
                                PlaylistSongsHeader(
                                    songCount = uiState.playlist?.songs?.size ?: 0,
                                    currentSortOrder = uiState.currentSortOrder,
                                    onSortOrderChange = { viewModel.onEvent(PlaylistDetailEvent.SetSortOrder(it)) },
                                    sortMenuExpanded = sortMenuExpanded,
                                    onSortMenuExpandedChange = { sortMenuExpanded = it },
                                    isTopSongs = uiState.playlist?.type == AutomaticPlaylistType.MOST_PLAYED
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                if (uiState.playlist?.songs.isNullOrEmpty()) {
                                    PlaylistEmptyState(modifier = Modifier.fillMaxSize(), playlistType = uiState.playlist?.type)
                                } else {
                                    PlaylistSongs(
                                        songs = uiState.sortedSongs,
                                        currentPlayingId = uiState.currentPlayingAudioFile?.id,
                                        onSongClick = { clickedAudioFile ->
                                            if (isSelectionMode && !isReadOnlyPlaylist) viewModel.onEvent(PlaylistDetailEvent.ToggleSelection(clickedAudioFile))
                                            else viewModel.onEvent(PlaylistDetailEvent.PlayAudio(clickedAudioFile))
                                        },
                                        onSongRemove = { song -> viewModel.onEvent(PlaylistDetailEvent.ShowRemoveSongConfirmation(song)) },
                                        listState = rightListState,
                                        isAudioPlaying = uiState.isPlaying,
                                        modifier = Modifier.fillMaxSize(),
                                        onAddToPlaylist = { viewModel.onEvent(PlaylistDetailEvent.ShowPlaylistsDialog(it)) },
                                        onPlayNext = { viewModel.onEvent(PlaylistDetailEvent.SetPlayNext(it)) },
                                        isFromAutomaticPlaylist = isReadOnlyPlaylist,
                                        playCountMap = uiState.playlist?.playCounts,
                                        onEditInfo = onEditInfo,
                                        onTrimAudio = onTrimAudio,
                                        isSelectionMode = isSelectionMode,
                                        selectedSongs = uiState.selectedSongs,
                                        onToggleSelection = { song -> viewModel.onEvent(PlaylistDetailEvent.ToggleSelection(song)) },
                                        onLongPress = { song ->
                                            if (!isSelectionMode && !isReadOnlyPlaylist) {
                                                viewModel.onEvent(PlaylistDetailEvent.ToggleSelection(song))
                                            } else {
                                                Toast.makeText(context, "Cannot select songs from this playlists.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Dialogs ───────────────────────────────────────────────────────────

        if (uiState.showSetCoverConfirmationDialog) {
            uiState.potentialCoverAudioFile?.let { audioFile ->
                ConfirmationDialog(
                    title = "Set Playlist Cover?",
                    message = "The album art from '${audioFile.title}' will be used as this playlist's cover.",
                    confirmButtonText = "Set Cover",
                    dismissButtonText = "Cancel",
                    onConfirm = { viewModel.onEvent(PlaylistDetailEvent.ConfirmSetCover) },
                    onDismiss = { viewModel.onEvent(PlaylistDetailEvent.DismissSetCoverConfirmation) }
                )
            }
        }

        if (uiState.showRenameDialog && uiState.playlist != null) {
            RenamePlaylistDialog(
                currentName = uiState.playlist?.name!!,
                onConfirm = { newName -> viewModel.onEvent(PlaylistDetailEvent.RenamePlaylist(newName)) },
                onDismiss = { viewModel.onEvent(PlaylistDetailEvent.HideRenameDialog) },
                errorMessage = uiState.error
            )
        }

        if (uiState.showAddToPlaylistDialog) {
            AddSongToPlaylistDialog(
                onDismiss = { viewModel.onEvent(PlaylistDetailEvent.DismissAddToPlaylistDialog) },
                playlists = uiState.playlists,
                onAddSongToPlaylist = { playlist -> viewModel.onEvent(PlaylistDetailEvent.AddedSongToPlaylist(playlist)) },
                onCreateNewPlaylist = { viewModel.onEvent(PlaylistDetailEvent.ShowCreatePlaylistDialog) }
            )
        }

        if (uiState.showRemoveSongConfirmationDialog) {
            uiState.audioFileToRemove?.let { audioFile ->
                ConfirmationDialog(
                    title = "Remove '${audioFile.title}'?",
                    message = "Are you sure you want to remove this song from '${uiState.playlist?.name}'?",
                    confirmButtonText = "Remove",
                    dismissButtonText = "Cancel",
                    onConfirm = { viewModel.onEvent(PlaylistDetailEvent.ConfirmRemoveSong) },
                    onDismiss = { viewModel.onEvent(PlaylistDetailEvent.DismissRemoveSongConfirmation) }
                )
            } ?: run {
                viewModel.onEvent(PlaylistDetailEvent.DismissRemoveSongConfirmation)
            }
        }

        if (uiState.showCreatePlaylistDialog) {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(PlaylistDetailEvent.DismissCreatePlaylistDialog) },
                title = { Text("Create Playlist") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Playlist Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.onEvent(PlaylistDetailEvent.CreatePlaylistAndAddSongs(text)) }) {
                        Text("Create & Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onEvent(PlaylistDetailEvent.DismissCreatePlaylistDialog) }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (uiState.showBatchRemoveConfirmationDialog) {
            LaunchedEffect(Unit) { allowBatchDismiss = false }
            ModalBottomSheet(
                onDismissRequest = {
                    viewModel.onEvent(PlaylistDetailEvent.DeselectAll)
                    viewModel.onEvent(PlaylistDetailEvent.DismissBatchRemoveConfirmation)
                },
                containerColor = MaterialTheme.colorScheme.surface,
                sheetState = batchSheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Remove ${pluralize(uiState.selectedSongs.size, "song", "songs")}?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This will remove the selected songs from the playlist (they will not be deleted from your device).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            viewModel.onEvent(PlaylistDetailEvent.DeselectAll)
                            allowBatchDismiss = true
                            coroutineScope.launch { batchSheetState.hide() }.invokeOnCompletion {
                                viewModel.onEvent(PlaylistDetailEvent.DismissBatchRemoveConfirmation)
                            }
                        }) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                allowBatchDismiss = true
                                coroutineScope.launch { batchSheetState.hide() }.invokeOnCompletion {
                                    viewModel.onEvent(PlaylistDetailEvent.ConfirmBatchRemove)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Confirm") }
                    }
                }
            }
        }
    }
}