package com.engfred.musicplayer.core.domain

/**
 * Decouples the library module from the dj_mix module.
 * NewAudioScanWorker calls this to trigger BPM pre-analysis
 * without importing anything from features:dj_mix.
 */
interface BpmScanScheduler {
    fun scheduleGlobalScan()
}