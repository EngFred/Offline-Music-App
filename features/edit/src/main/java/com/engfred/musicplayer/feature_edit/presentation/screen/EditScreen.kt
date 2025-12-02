package com.engfred.musicplayer.feature_edit.presentation.screen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_edit.presentation.components.EditView
import com.engfred.musicplayer.feature_edit.presentation.viewModel.EditViewModel
import com.engfred.musicplayer.feature_edit.presentation.components.CropDialog
import com.engfred.musicplayer.feature_edit.presentation.viewModel.EditUIEvent
import kotlinx.coroutines.flow.collectLatest

@Composable
fun EditScreen(
    audioId: Long,
    onFinish: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayPauseClick: () -> Unit,
    onMiniPlayNext: () -> Unit,
    onMiniPlayPrevious: () -> Unit,
    playingAudioFile: AudioFile?,
    isPlaying: Boolean,
    stopAfterCurrent: Boolean,
    onToggleStopAfterCurrent: () -> Unit,
    playbackPositionMs: Long,
    totalDurationMs: Long,
    viewModel: EditViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }

    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            showCropDialog = true
        }
    }

    // Function to launch picker (only gallery)
    val launchImagePicker = {
        galleryLauncher.launch("image/*")
    }

    if (showCropDialog && pickedUri != null) {
        CropDialog(
            imageUri = pickedUri!!,
            onCrop = { croppedUri ->
                viewModel.pickImage(croppedUri)
                showCropDialog = false
                pickedUri = null
            },
            onCancel = {
                showCropDialog = false
                pickedUri = null
            }
        )
    }

    // Launcher for IntentSender (used for createWriteRequest or RecoverableSecurityException)
    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.continueSaveAfterPermission(context)
        } else {
            Toast.makeText(context, "Access to song denied. Cannot edit.", Toast.LENGTH_LONG).show()
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
                is EditUIEvent.Success -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    onFinish()
                }
                is EditUIEvent.Error -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is EditUIEvent.RequestWritePermission -> {
                    val req = IntentSenderRequest.Builder(event.intentSender).build()
                    intentSenderLauncher.launch(req)
                }
            }
        }
    }

    // Load data and check basic READ permissions on entry
    LaunchedEffect(audioId) {
        viewModel.loadAudioFile(audioId)
        val perm = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(perm)
        }
    }

    EditView(
        uiState = state,
        onPickImage = launchImagePicker,
        onTitleChange = viewModel::updateTitle,
        onArtistChange = viewModel::updateArtist,
        onSave = { viewModel.saveChanges(audioId, context) },
        onCancel = onFinish,
        onMiniPlayerClick = onMiniPlayerClick,
        onMiniPlayPauseClick = onMiniPlayPauseClick,
        onMiniPlayNext = onMiniPlayNext,
        onMiniPlayPrevious = onMiniPlayPrevious,
        playingAudioFile = playingAudioFile,
        isPlaying = isPlaying,
        stopAfterCurrent = stopAfterCurrent,
        onMiniToggleStopAfterCurrent = onToggleStopAfterCurrent,
        playbackPositionMs = playbackPositionMs,
        totalDurationMs = totalDurationMs
    )
}