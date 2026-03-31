package com.engfred.musicplayer.feature_dj_mix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BpmCacheDao {

    // ── Single-file look-ups ──────────────────────────────────────────────────

    /** Returns the cached entry for one file, or null if not yet analysed. */
    @Query("SELECT * FROM bpm_cache WHERE audioFileId = :audioFileId")
    suspend fun getBpmForAudio(audioFileId: Long): BpmCacheEntity?

    // ── Batch look-ups ────────────────────────────────────────────────────────

    /**
     * Returns cached entries for a set of files.
     * Caller can diff against the full playlist to find which still need analysis.
     */
    @Query("SELECT * FROM bpm_cache WHERE audioFileId IN (:audioFileIds)")
    suspend fun getBpmForAudios(audioFileIds: List<Long>): List<BpmCacheEntity>

    // ── Reactive reads ────────────────────────────────────────────────────────

    /**
     * Emits the full cache whenever any row changes.
     * Observed by [DjMixViewModel] to track analysis progress and build the BPM map.
     */
    @Query("SELECT * FROM bpm_cache")
    fun getAllBpmEntries(): Flow<List<BpmCacheEntity>>

    // ── Writes ────────────────────────────────────────────────────────────────

    /** Inserts or replaces (re-analyses always win). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBpm(entity: BpmCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBpms(entities: List<BpmCacheEntity>)

    @Query("DELETE FROM bpm_cache WHERE audioFileId = :audioFileId")
    suspend fun deleteBpm(audioFileId: Long)

    @Query("DELETE FROM bpm_cache")
    suspend fun clearAll()
}