package com.engfred.musicplayer.feature_trim.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.feature_trim.presentation.components.TrimBottomAction
import com.engfred.musicplayer.feature_trim.presentation.components.TrimHeader
import com.engfred.musicplayer.feature_trim.presentation.components.TrimPlaybackControls
import com.engfred.musicplayer.feature_trim.presentation.components.TrimWaveformEditor
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

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CustomTopBar(
                modifier = Modifier.statusBarsPadding(),
                title = "Trim Audio",
                backgroundColor = MaterialTheme.colorScheme.background,
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
                .padding(padding)
        ) {
            val state = uiState
            if (state.audioFile != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Album Art & Info
                    TrimHeader(audioFile = state.audioFile)

                    Spacer(modifier = Modifier.height(28.dp))

                    // 2. Interactive Waveform
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

                    Spacer(modifier = Modifier.height(96.dp))
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

    // Polished 20dp Dialogs
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    imageVector = Icons.Rounded.ContentCut,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Save Trimmed Audio") },
            text = { Text("The trimmed audio will be saved as a new file in your music library.") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showSaveDialog = false
                        viewModel.trimAudio()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showConfirmBackDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmBackDialog = false },
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Cancel Trimming?") },
            text = { Text("This will stop the current trim operation.") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.cancelTrim()
                        showConfirmBackDialog = false
                        onNavigateUp()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Yes, Cancel")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmBackDialog = false }) {
                    Text("Keep Trimming")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}