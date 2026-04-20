package com.engfred.musicplayer.feature_dj_mix.presentation.components

// ─────────────────────────────────────────────────────────────────────────────
//  Shared enums & utilities for the DJ deck UI components
// ─────────────────────────────────────────────────────────────────────────────

internal enum class SyncState { SYNCED, CLOSE, OFF, UNKNOWN }

internal fun formatDeckMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}