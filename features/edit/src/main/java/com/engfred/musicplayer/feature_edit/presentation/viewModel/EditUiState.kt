package com.engfred.musicplayer.feature_edit.presentation.viewModel

import android.net.Uri
import com.engfred.musicplayer.core.domain.model.AudioFile

/**
 * UI state for the song editing screen.
 */
data class EditUiState(
    val title: String = "",
    val artist: String = "",
    val albumArtPreviewUri: Uri? = null,
    val isSaving: Boolean = false,
    val audioFile: AudioFile? = null
)