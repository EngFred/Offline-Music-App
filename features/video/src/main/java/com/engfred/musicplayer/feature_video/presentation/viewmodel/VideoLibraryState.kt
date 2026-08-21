package com.engfred.musicplayer.feature_video.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.CastState
import com.engfred.musicplayer.core.domain.model.VideoFile

enum class VideoSortOption {
    DATE_ADDED_DESC,
    DATE_ADDED_ASC,
    TITLE_ASC,
    TITLE_DESC,
    DURATION_DESC,
    SIZE_DESC
}

data class VideoLibraryState(
    val videos: List<VideoFile> = emptyList(),
    val filteredVideos: List<VideoFile> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val sortOption: VideoSortOption = VideoSortOption.DATE_ADDED_DESC,
    val isCastConnected: Boolean = false,
    val castState: CastState = CastState.DISCONNECTED,
    val selectedFolder: String? = null,
    val availableFolders: List<String> = emptyList(),
    val videoFileToDelete: VideoFile? = null,
    val showDeleteConfirmationDialog: Boolean = false,
    val error: String? = null
)
