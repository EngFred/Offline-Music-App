package com.engfred.musicplayer.feature_library.presentation.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.ui.components.AddSongToPlaylistDialog
import com.engfred.musicplayer.core.ui.components.ConfirmationDialog
import com.engfred.musicplayer.core.util.TextUtils.pluralize
import com.engfred.musicplayer.feature_library.presentation.components.LibraryContent
import com.engfred.musicplayer.feature_library.presentation.components.PermissionRequestContent
import com.engfred.musicplayer.feature_library.presentation.components.SearchBar
import com.engfred.musicplayer.feature_library.presentation.viewmodel.LibraryEvent
import com.engfred.musicplayer.feature_library.presentation.viewmodel.LibraryViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val permission = viewModel.getRequiredPermission()
    val permissionState = rememberPermissionState(permission)
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    val owner = LocalLifecycleOwner.current

    // Track if permission has been requested at least once
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    // Track if we're currently showing the permission dialog
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

    // React to permission state changes:
    // reset isPermissionDialogShowing whenever a response is observed (granted or denied)
    LaunchedEffect(permissionState.status) {
        isPermissionDialogShowing = false
        if (permissionState.status.isGranted) {
            viewModel.onEvent(LibraryEvent.PermissionGranted)
        } else {
            viewModel.onEvent(LibraryEvent.CheckPermission)
        }
    }

    DisposableEffect(key1 = Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onEvent(LibraryEvent.CheckPermission)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
        }
    }

    // Collect UI messages
    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Collect delete requests
    LaunchedEffect(viewModel.deleteRequest) {
        viewModel.deleteRequest.collect { intentSenderRequest ->
            deleteMediaLauncher.launch(intentSenderRequest)
        }
    }

    // --- helper states for showing scroll buttons ---
    val coroutineScope = rememberCoroutineScope()

    val currentListCount by remember(uiState) {
        derivedStateOf {
            val audios = uiState.filteredAudioFiles.ifEmpty { uiState.audioFiles }
            audios.size
        }
    }

    val isAtTop by remember(lazyListState) {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
        }
    }

    val isAtBottom by remember(lazyListState) {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                lastVisible?.index == total - 1
            }
        }
    }

    var userIsScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (inProgress) {
                    userIsScrolling = true
                } else {
                    val delayMillis = 1200L
                    try {
                        delay(delayMillis)
                        userIsScrolling = false
                    } catch (_: Exception) { }
                }
            }
    }

    val showScrollToTop by remember(userIsScrolling, isAtTop) { derivedStateOf { userIsScrolling && !isAtTop } }
    val showScrollToBottom by remember(userIsScrolling, isAtBottom, currentListCount) {
        derivedStateOf { userIsScrolling && !isAtBottom && currentListCount > 0 }
    }

    // --- UI ---
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!uiState.hasStoragePermission) {
                PermissionRequestContent(
                    shouldShowRationale = permissionState.status.shouldShowRationale,
                    isPermanentlyDenied = (!permissionState.status.isGranted &&
                            !permissionState.status.shouldShowRationale &&
                            hasRequestedPermission),
                    isPermissionDialogShowing = isPermissionDialogShowing,
                    onRequestPermission = {
                        permissionState.launchPermissionRequest()
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

                // BackHandler: when selection mode is active, consume Back and deselect all.
                BackHandler(enabled = isSelectionMode) {
                    viewModel.onEvent(LibraryEvent.DeselectAll)
                }

                if (isSelectionMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
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
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Row {
                                val allSelected = uiState.selectedAudioFiles.size == uiState.filteredAudioFiles.size
                                IconButton(onClick = {
                                    if (allSelected) viewModel.onEvent(LibraryEvent.DeselectAll)
                                    else viewModel.onEvent(LibraryEvent.SelectAll)
                                }) {
                                    Icon(
                                        if (allSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                        contentDescription = "Select all"
                                    )
                                }
                                IconButton(onClick = { viewModel.onEvent(LibraryEvent.ShowBatchDeleteConfirmation) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete selected")
                                }
                            }
                        }
                    }
                } else {
                    SearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = { query -> viewModel.onEvent(LibraryEvent.SearchQueryChanged(query)) },
                        placeholder = "Search songs",
                        currentFilter = uiState.currentFilterOption,
                        onFilterSelected = { filterOption -> viewModel.onEvent(LibraryEvent.FilterSelected(filterOption)) }
                    )
                }

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

        // Floating buttons column (unchanged)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = slideInVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(180))
            ) {
                FloatingActionButton(
                    onClick = { coroutineScope.launch { lazyListState.scrollToItem(index = 0) } },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
                }
            }

            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = slideInVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(180))
            ) {
                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            val lastIndex = (currentListCount - 1).coerceAtLeast(0)
                            lazyListState.scrollToItem(index = lastIndex)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
            }
        }
    }

    // Dialogs & Floating flows (unchanged)
    if (uiState.showAddToPlaylistDialog) {
        AddSongToPlaylistDialog(
            onDismiss = { viewModel.onEvent(LibraryEvent.DismissAddToPlaylistDialog) },
            playlists = uiState.playlists,
            onAddSongToPlaylist = { playlist -> viewModel.onEvent(LibraryEvent.AddedSongToPlaylist(playlist)) }
        )
    }

    if (uiState.showDeleteConfirmationDialog) {
        uiState.audioFileToDelete?.let { audioFile ->
            ConfirmationDialog(
                title = "Delete '${audioFile.title}'?",
                message = "Are you sure you want to permanently delete '${audioFile.title}' from your device? This action cannot be undone.",
                confirmButtonText = "Delete",
                dismissButtonText = "Cancel",
                onConfirm = { viewModel.onEvent(LibraryEvent.ConfirmDeleteAudioFile) },
                onDismiss = { viewModel.onEvent(LibraryEvent.DismissDeleteConfirmationDialog) }
            )
        } ?: run {
            viewModel.onEvent(LibraryEvent.DismissDeleteConfirmationDialog)
        }
    }

    // New batch delete bottom sheet
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allowDismiss by remember { mutableStateOf(false) }  // Control outside dismiss
    if (uiState.showBatchDeleteConfirmationDialog) {
        LaunchedEffect(Unit) { allowDismiss = false }

        ModalBottomSheet(
            onDismissRequest = {
                // When the sheet is dismissed by tapping outside, dismiss and also deselect everything.
                // This mirrors the Cancel button behaviour.
                viewModel.onEvent(LibraryEvent.DeselectAll)
                viewModel.onEvent(LibraryEvent.DismissDeleteConfirmationDialog)
            },
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Delete ${pluralize(uiState.selectedAudioFiles.size, "song", "songs")}?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This action will permanently delete the selected songs from your device and cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        // Cancel: mirror tapping outside; deselect everything then dismiss
                        viewModel.onEvent(LibraryEvent.DeselectAll)
                        allowDismiss = true
                        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                            viewModel.onEvent(LibraryEvent.DismissDeleteConfirmationDialog)
                        }
                    }) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // Confirm: perform batch delete; ViewModel should clear selection after success.
                            allowDismiss = true
                            coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                                viewModel.onEvent(LibraryEvent.ConfirmBatchDelete)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}
