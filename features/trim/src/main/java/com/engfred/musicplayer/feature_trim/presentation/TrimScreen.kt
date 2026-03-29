package com.engfred.musicplayer.feature_trim.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.feature_trim.presentation.components.TrimHeader
import com.engfred.musicplayer.feature_trim.presentation.components.TrimWaveformEditor
import com.engfred.musicplayer.feature_trim.presentation.components.TrimPlaybackControls
import com.engfred.musicplayer.feature_trim.presentation.components.TrimBottomAction
import kotlinx.coroutines.flow.collectLatest

@Composable
fun TrimScreen(
    onNavigateUp: () -> Unit,
    viewModel: TrimViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsState()
    val previewPosition by viewModel.previewPositionMs.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var showConfirmBackDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = uiState.isTrimming) {
        showConfirmBackDialog = true
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is TrimViewModel.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is TrimViewModel.UiEvent.TrimSuccess -> snackbarHostState.showSnackbar("Trim completed successfully")
            }
        }
    }

    // A subtle gradient background for a premium feel
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    )

    Scaffold(
        topBar = {
            CustomTopBar(
                modifier = Modifier.statusBarsPadding(),
                title = "Trim File",
                onNavigateBack = {
                    if (uiState.isTrimming) showConfirmBackDialog = true else onNavigateUp()
                },
                showNavigationIcon = true
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .padding(padding)
        ) {
            val state = uiState
            if (state.audioFile != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Premium Header (Album Art & Info)
                    TrimHeader(audioFile = state.audioFile)

                    Spacer(modifier = Modifier.height(32.dp))

                    // 2. The Core Interactive Waveform
                    TrimWaveformEditor(
                        durationMs = state.audioFile.duration,
                        startMs = state.startTimeMs,
                        endMs = state.endTimeMs,
                        currentPositionMs = previewPosition + state.startTimeMs,
                        onStartChange = { viewModel.updateStartTime(it) },
                        onEndChange = { viewModel.updateEndTime(it) }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 3. Playback & Reset Controls
                    TrimPlaybackControls(
                        isPlaying = isPreviewPlaying,
                        onTogglePlay = { viewModel.togglePreview() },
                        onSeekToStart = { viewModel.seekPreviewToStart() },
                        onReset = { viewModel.resetTrim() },
                        isTrimming = state.isTrimming
                    )

                    // Error display
                    AnimatedVisibility(visible = state.error != null) {
                        Text(
                            text = state.error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(100.dp)) // Padding for bottom action
                }

                // 4. Floating Action Bar at the bottom
                TrimBottomAction(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    state = state,
                    onSaveClicked = { showSaveDialog = true }
                )

            } else if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    // Dialogs remain standard Material 3
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Trimmed File") },
            text = { Text("The trimmed file will be saved alongside the original file.") },
            confirmButton = {
                TextButton(onClick = { showSaveDialog = false; viewModel.trimAudio() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showConfirmBackDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmBackDialog = false },
            title = { Text("Cancel Trimming?") },
            text = { Text("This will stop the trim operation.") },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelTrim(); showConfirmBackDialog = false; onNavigateUp() }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmBackDialog = false }) { Text("No") }
            }
        )
    }
}