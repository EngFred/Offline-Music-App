package com.engfred.musicplayer.feature_library.presentation.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.ui.components.AddSongToPlaylistDialog
import com.engfred.musicplayer.core.ui.components.CastMediaRouteButton
import com.engfred.musicplayer.core.ui.components.ConfirmationDialog
import com.engfred.musicplayer.feature_library.presentation.components.LibraryContent
import com.engfred.musicplayer.feature_library.presentation.components.PermissionRequestContent
import com.engfred.musicplayer.feature_library.presentation.components.SearchBar
import com.engfred.musicplayer.feature_library.presentation.viewmodel.LibraryEvent
import com.engfred.musicplayer.feature_library.presentation.viewmodel.LibraryViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onEditSong: (AudioFile) -> Unit,
    onTrimAudio: (AudioFile) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    val owner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val permissionsToRequest = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    val permissionState = rememberMultiplePermissionsState(permissionsToRequest)
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    var isPermissionDialogShowing by rememberSaveable { mutableStateOf(false) }

    val deleteMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deletedAudioFile = uiState.audioFileToDelete
        val isBatch = uiState.selectedAudioFiles.isNotEmpty() || uiState.showBatchDeleteConfirmationDialog
        if (result.resultCode == Activity.RESULT_OK) {
            if (isBatch) {
                viewModel.onEvent(LibraryEvent.BatchDeletionResult(true, null))
            } else {
                deletedAudioFile?.let {
                    viewModel.onEvent(LibraryEvent.DeletionResult(it, true, null))
                }
            }
        } else {
            if (isBatch) {
                viewModel.onEvent(LibraryEvent.BatchDeletionResult(false, "Deletion cancelled or failed."))
            } else {
                deletedAudioFile?.let {
                    viewModel.onEvent(LibraryEvent.DeletionResult(it, false, "Deletion cancelled or failed."))
                } ?: run {
                    viewModel.onEvent(LibraryEvent.DismissDeleteConfirmationDialog)
                }
            }
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        isPermissionDialogShowing = false
        if (permissionState.allPermissionsGranted) {
            viewModel.onEvent(LibraryEvent.PermissionGranted)
        } else {
            val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

            val isStorageGranted = permissionState.permissions
                .find { it.permission == storagePermission }
                ?.status?.isGranted == true

            if (isStorageGranted) {
                viewModel.onEvent(LibraryEvent.PermissionGranted)
            } else {
                viewModel.onEvent(LibraryEvent.CheckPermission)
            }
        }
    }

    DisposableEffect(key1 = Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onEvent(LibraryEvent.CheckPermission)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel.deleteRequest) {
        viewModel.deleteRequest.collect { intentSenderRequest ->
            deleteMediaLauncher.launch(intentSenderRequest)
        }
    }

    val showScrollToTop by remember(lazyListState) {
        derivedStateOf { lazyListState.firstVisibleItemIndex > 2 }
    }

    val currentAudios = remember(uiState.filteredAudioFiles, uiState.audioFiles) {
        uiState.filteredAudioFiles.ifEmpty { uiState.audioFiles }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!uiState.hasStoragePermission) {
                PermissionRequestContent(
                    shouldShowRationale = permissionState.shouldShowRationale,
                    isPermanentlyDenied = (!permissionState.allPermissionsGranted && !permissionState.shouldShowRationale && hasRequestedPermission),
                    isPermissionDialogShowing = isPermissionDialogShowing,
                    onRequestPermission = {
                        permissionState.launchMultiplePermissionRequest()
                        hasRequestedPermission = true
                        isPermissionDialogShowing = true
                    },
                    onOpenAppSettings = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                )
            } else {
                val isSelectionMode = uiState.selectedAudioFiles.isNotEmpty()

                BackHandler(enabled = isSelectionMode) {
                    viewModel.onEvent(LibraryEvent.DeselectAll)
                }

                BackHandler(enabled = !isSelectionMode && uiState.searchQuery.isNotEmpty()) {
                    viewModel.onEvent(LibraryEvent.SearchQueryChanged(""))
                    focusManager.clearFocus()
                }

                // ── Selection Header vs Normal Hero Section ───────────────────
                if (isSelectionMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.onEvent(LibraryEvent.DeselectAll) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${uiState.selectedAudioFiles.size} selected",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            Row {
                                val allSelected = uiState.selectedAudioFiles.size == currentAudios.size

                                IconButton(onClick = {
                                    if (allSelected) viewModel.onEvent(LibraryEvent.DeselectAll)
                                    else viewModel.onEvent(LibraryEvent.SelectAll)
                                }) {
                                    Icon(
                                        if (allSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                        contentDescription = "Select all"
                                    )
                                }

                                IconButton(onClick = {
                                    uiState.selectedAudioFiles.firstOrNull()?.let {
                                        viewModel.onEvent(LibraryEvent.AddedToPlaylist(it))
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                        contentDescription = "Add selected to playlist",
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                IconButton(onClick = { viewModel.onEvent(LibraryEvent.ShowBatchDeleteConfirmation) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Delete selected",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── Normal Hero Header Section ───────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Library",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                ) {
                                    Text(
                                        text = "${currentAudios.size} Tracks",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            // Action Buttons (Play All & Cast button)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentAudios.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            val targetTrack = currentAudios.find { it.id == uiState.currentPlayingId } ?: currentAudios.firstOrNull()
                                            targetTrack?.let {
                                                viewModel.onEvent(LibraryEvent.PlayAudio(it))
                                            }
                                        },
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = "Play all",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Play",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        )
                                    }
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                                    ),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        CastMediaRouteButton(
                                            tintColor = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        SearchBar(
                            query = uiState.searchQuery,
                            onQueryChange = { query -> viewModel.onEvent(LibraryEvent.SearchQueryChanged(query)) },
                            placeholder = "Search track titles, artists...",
                            currentFilter = uiState.currentFilterOption,
                            onFilterSelected = { filterOption -> viewModel.onEvent(LibraryEvent.FilterSelected(filterOption)) }
                        )
                    }
                }

                // ── Library Content ─────────────────────────────────────────
                LibraryContent(
                    uiState = uiState,
                    onAudioClick = { audioFile ->
                        if (isSelectionMode) viewModel.onEvent(LibraryEvent.ToggleSelection(audioFile))
                        else viewModel.onEvent(LibraryEvent.PlayAudio(audioFile))
                    },
                    isAudioPlaying = uiState.isPlaying,
                    onRetry = { viewModel.onEvent(LibraryEvent.Retry) },
                    onRemoveOrDelete = { audioFileToDelete ->
                        if (isSelectionMode) viewModel.onEvent(LibraryEvent.ToggleSelection(audioFileToDelete))
                        else viewModel.onEvent(LibraryEvent.ShowDeleteConfirmation(audioFileToDelete))
                    },
                    onAddToPlaylist = { viewModel.onEvent(LibraryEvent.AddedToPlaylist(it)) },
                    onPlayNext = { viewModel.onEvent(LibraryEvent.PlayedNext(it)) },
                    lazyListState = lazyListState,
                    onEditSong = onEditSong,
                    onTrimAudio = onTrimAudio,
                    isSelectionMode = isSelectionMode,
                    selectedAudioFiles = uiState.selectedAudioFiles,
                    onToggleSelection = { audioFile -> viewModel.onEvent(LibraryEvent.ToggleSelection(audioFile)) },
                    onLongPress = { audioFile ->
                        if (!isSelectionMode) viewModel.onEvent(LibraryEvent.ToggleSelection(audioFile))
                    }
                )
            }
        }

        // Floating Action Button (Scroll to Top)
        AnimatedVisibility(
            visible = showScrollToTop,
            enter = scaleIn(animationSpec = tween(200)) + fadeIn(),
            exit = scaleOut(animationSpec = tween(150)) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 28.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { coroutineScope.launch { lazyListState.animateScrollToItem(index = 0) } },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Scroll to top",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────
    if (uiState.showAddToPlaylistDialog) {
        AddSongToPlaylistDialog(
            playlists = uiState.playlists,
            onDismiss = { viewModel.onEvent(LibraryEvent.DismissAddToPlaylistDialog) },
            onAddSongToPlaylist = { playlist -> viewModel.onEvent(LibraryEvent.AddedSongToPlaylist(playlist)) },
            onCreateNewPlaylist = { viewModel.onEvent(LibraryEvent.ShowCreatePlaylistDialog) }
        )
    }

    if (uiState.showCreatePlaylistDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(LibraryEvent.DismissCreatePlaylistDialog) },
            title = {
                Text(
                    text = "New Playlist",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Playlist Name") },
                    placeholder = { Text("e.g. Favorites") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            viewModel.onEvent(LibraryEvent.CreatePlaylistAndAddSongs(text))
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Create & Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(LibraryEvent.DismissCreatePlaylistDialog) }
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (uiState.showDeleteConfirmationDialog) {
        ConfirmationDialog(
            title = "Delete Song",
            message = "Are you sure you want to delete '${uiState.audioFileToDelete?.title}' from your device? This action cannot be undone.",
            confirmButtonText = "Delete",
            dismissButtonText = "Cancel",
            onConfirm = { viewModel.onEvent(LibraryEvent.ConfirmDeleteAudioFile) },
            onDismiss = { viewModel.onEvent(LibraryEvent.DismissDeleteConfirmationDialog) }
        )
    }

    if (uiState.showBatchDeleteConfirmationDialog) {
        ConfirmationDialog(
            title = "Delete ${uiState.selectedAudioFiles.size} Songs",
            message = "Are you sure you want to delete ${uiState.selectedAudioFiles.size} selected songs from your device? This action cannot be undone.",
            confirmButtonText = "Delete All",
            dismissButtonText = "Cancel",
            onConfirm = { viewModel.onEvent(LibraryEvent.ConfirmBatchDelete) },
            onDismiss = { viewModel.onEvent(LibraryEvent.DismissDeleteConfirmationDialog) }
        )
    }
}