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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_edit.presentation.components.CropView
import com.engfred.musicplayer.feature_edit.presentation.components.EditView
import com.engfred.musicplayer.feature_edit.presentation.viewModel.EditUIEvent
import com.engfred.musicplayer.feature_edit.presentation.viewModel.EditViewModel
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
    var isCropping by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            isCropping = true
        }
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.continueSaveAfterPermission(context)
        } else {
            Toast.makeText(context, "Access to song denied. Cannot edit.", Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (!granted) {
            Toast.makeText(context, "Storage permission denied. Cannot load song info.", Toast.LENGTH_LONG).show()
            onFinish()
        }
    }

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

    LaunchedEffect(audioId) {
        viewModel.loadAudioFile(audioId)
        val perm = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        if (!granted) permissionLauncher.launch(perm)
    }

    // Smooth transition between cropping and editing
    Crossfade(
        targetState = isCropping && pickedUri != null,
        animationSpec = tween(400),
        label = "crop_edit_transition"
    ) { showCrop ->
        if (showCrop) {
            CropView(
                imageUri = pickedUri!!, // Safe now because we don't nullify pickedUri during the fade-out
                onCrop = { croppedUri ->
                    viewModel.pickImage(croppedUri)
                    isCropping = false
                },
                onCancel = {
                    isCropping = false
                }
            )
        } else {
            EditView(
                uiState = state,
                onPickImage = { galleryLauncher.launch("image/*") },
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
    }
}