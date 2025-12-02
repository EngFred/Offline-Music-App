package com.engfred.musicplayer.feature_trim.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.feature_trim.presentation.components.AudioInfoCard
import com.engfred.musicplayer.feature_trim.presentation.components.CustomTrimLoadingIndicator
import com.engfred.musicplayer.feature_trim.presentation.components.TrimSlider
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
    val scope = rememberCoroutineScope()

    // Handle Back while trimming
    BackHandler(enabled = uiState.isTrimming) {
        showConfirmBackDialog = true
    }

    // Collect one-time UI events from ViewModel (success / error / permission denied)
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is TrimViewModel.UiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is TrimViewModel.UiEvent.TrimSuccess -> {
                    // optionally show a longer success message
                    snackbarHostState.showSnackbar("Trim completed successfully")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CustomTopBar(
                modifier = Modifier.statusBarsPadding(),
                title = "Trim Audio",
                onNavigateBack = {
                    if (uiState.isTrimming) {
                        showConfirmBackDialog = true
                    } else {
                        onNavigateUp()
                    }
                },
                showNavigationIcon = true
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .animateContentSize()
        ) {
            val state = uiState
            state.audioFile?.let { audioFile ->
                AudioInfoCard(audioFile = audioFile)

                Spacer(modifier = Modifier.height(16.dp))

                // Trim slider UI
                TrimSlider(
                    durationMs = audioFile.duration,
                    startMs = state.startTimeMs,
                    endMs = state.endTimeMs,
                    currentPositionMs = previewPosition + state.startTimeMs,
                    isPlaying = isPreviewPlaying,
                    onStartChange = { viewModel.updateStartTime(it) },
                    onEndChange = { viewModel.updateEndTime(it) },
                    onTogglePlay = { viewModel.togglePreview() },
                    onSeekToStart = { viewModel.seekPreviewToStart() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Error display (persistent)
                state.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Reset button
                OutlinedButton(
                    onClick = { viewModel.resetTrim() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isTrimming
                ) {
                    Text("Reset")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Save button: clearly disable when trimming, invalid, or trimmed already
                val trimDurationMs = state.endTimeMs - state.startTimeMs
                val originalDuration = audioFile.duration
                val isTrimmed = trimDurationMs < originalDuration
                val hasCriticalError = state.error != null && !state.error.contains("File too large")
                Button(
                    onClick = { showSaveDialog = true },
                    enabled = !state.isTrimming && !hasCriticalError && trimDurationMs >= 30_000L && state.trimResult == null && isTrimmed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.isTrimming) "Trimming..." else "Save Trimmed File")
                }

                // Trim result display (if success)
                state.trimResult?.let { _ ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Trim Successful!", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "The trimmed file was saved to your library.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Loading indicator overlay while trimming
                if (state.isTrimming) {
                    Spacer(modifier = Modifier.weight(1f).height(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CustomTrimLoadingIndicator()
                    }
                }

            } ?: if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // optional empty state
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    state.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } ?: Text("No audio file selected")
                }
            }
        }
    }

    // Save Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Trimmed File") },
            text = {
                Text("The trimmed file will be saved alongside the original file.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveDialog = false
                        viewModel.trimAudio()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm back dialog during trimming
    if (showConfirmBackDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmBackDialog = false },
            title = { Text("Cancel Trimming?") },
            text = { Text("This will stop the trim operation.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelTrim()
                    showConfirmBackDialog = false
                    onNavigateUp()
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmBackDialog = false }) { Text("No") }
            }
        )
    }
}