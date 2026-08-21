package com.engfred.musicplayer.feature_video.presentation.screens

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.VideoFile
import com.engfred.musicplayer.core.ui.components.ConfirmationDialog
import com.engfred.musicplayer.core.ui.components.shimmerBrush
import com.engfred.musicplayer.feature_video.presentation.components.VideoFileItem
import com.engfred.musicplayer.feature_video.presentation.components.VideoTopBar
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoLibraryEvent
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoLibraryViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VideoLibraryScreen(
    onVideoClick: (VideoFile) -> Unit,
    onCastVideo: ((VideoFile) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: VideoLibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val videoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionState = rememberPermissionState(permission = videoPermission)

    val deleteVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val videoFile = uiState.videoFileToDelete
        if (result.resultCode == Activity.RESULT_OK) {
            videoFile?.let {
                viewModel.onEvent(VideoLibraryEvent.DeletionResult(it, true, null))
            }
        } else {
            videoFile?.let {
                viewModel.onEvent(
                    VideoLibraryEvent.DeletionResult(
                        videoFile = it,
                        success = false,
                        errorMessage = "Deletion cancelled or failed."
                    )
                )
            } ?: viewModel.onEvent(VideoLibraryEvent.DismissDeleteConfirmationDialog)
        }
    }

    // Automatically refresh videos the instant the user grants permission
    LaunchedEffect(permissionState.status.isGranted) {
        if (permissionState.status.isGranted) {
            viewModel.onEvent(VideoLibraryEvent.Refresh)
        }
    }

    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel.deleteRequest) {
        viewModel.deleteRequest.collect { intentSenderRequest ->
            deleteVideoLauncher.launch(intentSenderRequest)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VideoTopBar(
                totalVideosCount = uiState.videos.size,
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = { viewModel.onEvent(VideoLibraryEvent.OnSearchQueryChange(it)) },
                currentSortOption = uiState.sortOption,
                onSortOptionChange = { viewModel.onEvent(VideoLibraryEvent.OnSortOptionChange(it)) },
                availableFolders = uiState.availableFolders,
                selectedFolder = uiState.selectedFolder,
                onFolderSelect = { viewModel.onEvent(VideoLibraryEvent.OnFolderSelect(it)) }
            )

            if (!permissionState.status.isGranted) {
                // Permission Request Screen
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Permission Required",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please grant permission to scan and play video files on your device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { permissionState.launchPermissionRequest() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            } else if (uiState.isLoading) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(8) {
                        ShimmerVideoFileItem()
                    }
                }
            } else if (uiState.filteredVideos.isEmpty()) {
                // Empty State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VideocamOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No matching videos" else "No videos found",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "Try searching for something else" else "Videos on your device will appear here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Videos Grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = uiState.filteredVideos,
                        key = { it.id }
                    ) { video ->
                        VideoFileItem(
                            videoFile = video,
                            onClick = { onVideoClick(video) },
                            onCastVideo = if (uiState.isCastConnected) onCastVideo else null,
                            onDeleteVideo = {
                                viewModel.onEvent(VideoLibraryEvent.ShowDeleteConfirmation(it))
                            }
                        )
                    }
                }
            }
        }
    }

    if (uiState.showDeleteConfirmationDialog) {
        ConfirmationDialog(
            title = "Delete Video",
            message = "Are you sure you want to delete '${uiState.videoFileToDelete?.title}' from your device? This action cannot be undone.",
            confirmButtonText = "Delete",
            dismissButtonText = "Cancel",
            onConfirm = { viewModel.onEvent(VideoLibraryEvent.ConfirmDeleteVideoFile) },
            onDismiss = { viewModel.onEvent(VideoLibraryEvent.DismissDeleteConfirmationDialog) }
        )
    }
}

@Composable
private fun ShimmerVideoFileItem(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(bottom = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(brush, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(16.dp)
                .padding(horizontal = 10.dp)
                .background(brush, RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.height(7.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.48f)
                .height(12.dp)
                .padding(horizontal = 10.dp)
                .background(brush, RoundedCornerShape(5.dp))
        )
    }
}
