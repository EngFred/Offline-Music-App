package com.engfred.musicplayer.feature_audio_trim.domain.model

sealed class TrimResult {
    data object Success : TrimResult()
    data class Error(val message: String) : TrimResult()
    data object PermissionDenied : TrimResult()
}