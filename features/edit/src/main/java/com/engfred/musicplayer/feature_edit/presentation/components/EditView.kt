package com.engfred.musicplayer.feature_edit.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.core.ui.components.MiniPlayer
import com.engfred.musicplayer.feature_edit.presentation.viewModel.EditUiState

@Composable
fun EditView(
    uiState: EditUiState,
    onPickImage: () -> Unit,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayPauseClick: () -> Unit,
    onMiniPlayNext: () -> Unit,
    onMiniPlayPrevious: () -> Unit,
    playingAudioFile: AudioFile?,
    isPlaying: Boolean,
    stopAfterCurrent: Boolean,
    onMiniToggleStopAfterCurrent: () -> Unit,
    playbackPositionMs: Long,
    totalDurationMs: Long,
) {
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.imePadding(),
        topBar = {
            CustomTopBar(
                title = "Edit Metadata",
                showNavigationIcon = true,
                onNavigateBack = onCancel,
                backgroundColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.statusBarsPadding()
            )
        },
        bottomBar = {
            if (playingAudioFile != null) {
                MiniPlayer(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = onMiniPlayerClick,
                    onPlayPause = onMiniPlayPauseClick,
                    onPlayNext = onMiniPlayNext,
                    onPlayPrev = onMiniPlayPrevious,
                    playingAudioFile = playingAudioFile,
                    isPlaying = isPlaying,
                    stopAfterCurrent = stopAfterCurrent,
                    onToggleStopAfterCurrent = onMiniToggleStopAfterCurrent,
                    playbackPositionMs = playbackPositionMs,
                    totalDurationMs = totalDurationMs
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // 1. Album Art Selector with Floating Action Badge
            EditableAlbumArt(
                imageUri = uiState.albumArtPreviewUri,
                onClick = onPickImage
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 2. Input Fields
            EditMetadataForm(
                title = uiState.title,
                artist = uiState.artist,
                onTitleChange = onTitleChange,
                onArtistChange = onArtistChange
            )

            Spacer(modifier = Modifier.height(40.dp))

            // 3. Save Action
            EditActionSection(
                isSaving = uiState.isSaving,
                onSave = onSave
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}