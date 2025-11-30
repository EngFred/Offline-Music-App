package com.engfred.musicplayer.feature_library.presentation.screens

import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.core.ui.components.MiniPlayer
import com.engfred.musicplayer.feature_library.presentation.viewmodel.EditFileUiState
import com.engfred.musicplayer.feature_library.presentation.viewmodel.EditFileViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun EditAudioInfoScreenContainer(
    audioId: Long,
    onFinish: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayPauseClick: () -> Unit,
    onMiniPlayNext: () -> Unit,
    onMiniPlayPrevious: () -> Unit,
    playingAudioFile: AudioFile?,
    isPlaying: Boolean,
    viewModel: EditFileViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // Launcher to pick an image
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let { viewModel.pickImage(it) } }
    )

    // Launcher for IntentSender (used for createWriteRequest or RecoverableSecurityException)
    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            // After user grants per-file access, proceed with saving flow
            viewModel.continueSaveAfterPermission(context)
        } else {
            Toast.makeText(context, "Access to song denied. Cannot edit.", Toast.LENGTH_LONG).show()
            // We don't force finish here anymore, user might want to try again or change something else
        }
    }

    // Launcher for runtime permission (read/query for Q+; write for pre-Q)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (!granted) {
            Toast.makeText(context, "Storage permission denied. Cannot load song info.", Toast.LENGTH_LONG).show()
            onFinish()
        }
    }

    // Collect ViewModel events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is EditFileViewModel.Event.Success -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    onFinish()
                }
                is EditFileViewModel.Event.Error -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is EditFileViewModel.Event.RequestWritePermission -> {
                    // Launch system prompt for RecoverableSecurityException when SAVE is clicked
                    val req = IntentSenderRequest.Builder(event.intentSender).build()
                    intentSenderLauncher.launch(req)
                }
            }
        }
    }

    // Load data and check basic READ permissions on entry
    LaunchedEffect(audioId) {
        viewModel.loadAudioFile(audioId)

        // Determine runtime permission to request (read for Q+ query; write for pre-Q)
        // Note: For Android 13+ (Tiramisu), READ_MEDIA_AUDIO is sufficient to query.
        // Editing is handled via RecoverableSecurityException or createWriteRequest during save.
        val perm = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> android.Manifest.permission.READ_MEDIA_AUDIO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> android.Manifest.permission.READ_EXTERNAL_STORAGE
            else -> android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

        // Removed the proactive request for write access on Android R+.
        // We only request the basic READ permission if not granted.
        // Write permission will be requested "Just-In-Time" when the user clicks SAVE.
        if (!granted) {
            permissionLauncher.launch(perm)
        }
    }

    EditFileInfoScreen(
        uiState = state,
        onPickImage = { pickImageLauncher.launch("image/*") },
        onTitleChange = viewModel::updateTitle,
        onArtistChange = viewModel::updateArtist,
        onSave = { viewModel.saveChanges(audioId, context) },
        onCancel = onFinish,
        onMiniPlayerClick = onMiniPlayerClick,
        onMiniPlayPauseClick = onMiniPlayPauseClick,
        onMiniPlayNext = onMiniPlayNext,
        onMiniPlayPrevious = onMiniPlayPrevious,
        playingAudioFile = playingAudioFile,
        isPlaying = isPlaying
    )
}

@Composable
fun EditFileInfoScreen(
    uiState: EditFileUiState,
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
    isPlaying: Boolean
) {
    Scaffold(
        topBar = {
            CustomTopBar("Edit Audio Info", showNavigationIcon = true, onNavigateBack = {
                onCancel()
            }, modifier = Modifier.statusBarsPadding())
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
                    isPlaying = isPlaying
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.size(200.dp),
                shape = CircleShape,
                shadowElevation = 4.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val imageUri = uiState.albumArtPreviewUri
                    if (imageUri != null) {
                        AsyncImage(
                            model = imageUri,
                            contentDescription = "Album art preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = "No album art",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(80.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onPickImage) {
                Text(text = "Change Album Art")
            }
            Spacer(modifier = Modifier.height(24.dp))
            TextField(
                value = uiState.title,
                onValueChange = onTitleChange,
                label = { Text("Song Title") },
                leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextField(
                value = uiState.artist,
                onValueChange = onArtistChange,
                label = { Text("Artist") },
                leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (uiState.isSaving) {
                CircularProgressIndicator()
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save changes", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "The changes will be applied system-wide even across other applications that have access to this file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}