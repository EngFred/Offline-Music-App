package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.engfred.musicplayer.core.domain.BpmScanScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class BpmScanSchedulerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : BpmScanScheduler {
    override fun scheduleGlobalScan() {
        GlobalBpmScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE)
    }
}