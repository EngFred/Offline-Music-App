package com.engfred.musicplayer.core.domain.model

data class UpdateInfo(
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val htmlUrl: String
)