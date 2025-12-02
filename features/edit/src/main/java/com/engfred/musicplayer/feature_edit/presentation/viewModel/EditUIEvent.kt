package com.engfred.musicplayer.feature_edit.presentation.viewModel

import android.content.IntentSender

sealed class EditUIEvent {
    data class Success(val message: String) : EditUIEvent()
    data class Error(val message: String) : EditUIEvent()
    data class RequestWritePermission(val intentSender: IntentSender) : EditUIEvent()
}