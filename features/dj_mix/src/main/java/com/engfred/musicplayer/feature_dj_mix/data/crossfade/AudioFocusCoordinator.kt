package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages Android audio focus on behalf of [CrossfadeEngine]'s two ExoPlayer instances.
 *
 * ── Problem this solves ───────────────────────────────────────────────────────
 * [CrossfadeEngine] runs two ExoPlayer instances simultaneously during a crossfade.
 * If each player requested audio focus independently (handleAudioFocus = true on
 * both), they would fight each other: player B gaining focus would trigger a
 * AUDIOFOCUS_LOSS event on player A, pausing it mid-fade.
 *
 * Solution: both players are built with handleAudioFocus = false. This class owns
 * the SINGLE audio focus request for the entire engine and coordinates pause/resume
 * across both players when the system interrupts (phone call, navigation voice,
 * another music app). The user hears complete silence during an interruption rather
 * than one track stopping and the other continuing unexpectedly.
 *
 * ── Focus events handled ──────────────────────────────────────────────────────
 * AUDIOFOCUS_GAIN              → Playback resumes if it was interrupted.
 * AUDIOFOCUS_LOSS              → Pause both players permanently (another app took over).
 * AUDIOFOCUS_LOSS_TRANSIENT    → Pause both players briefly (navigation voice, etc.).
 * AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK → Treated as LOSS_TRANSIENT for a DJ mix.
 *   Ducking music in a DJ context (dropping volume by a few dB while a notification
 *   plays) is more distracting than a clean pause/resume, so we always fully pause.
 *
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 * [request]  → call from CrossfadeEngine.startPlayback() before play().
 * [abandon]  → call from CrossfadeEngine.release() after stopping both players.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * [request] and [abandon] are safe from any thread.
 * [Listener] callbacks are delivered on the main thread by AudioManager.
 */
internal class AudioFocusCoordinator(
    context: Context,
    private val listener: Listener
) {

    /**
     * Callbacks delivered to [CrossfadeEngine] when Android audio focus changes.
     * All callbacks are delivered on the main thread.
     */
    interface Listener {
        /**
         * Another app has permanently taken audio focus (e.g. a phone call accepted,
         * another music app started). Pause all players immediately.
         */
        fun onFocusLost()

        /**
         * Audio focus is temporarily lost (e.g. navigation prompt, incoming call ringing).
         * Pause all players. [onFocusGained] will be called when the interruption ends.
         */
        fun onFocusLostTransient()

        /**
         * Audio focus has been returned. Resume playback if it was interrupted by the system
         * (not if the user manually paused before the interruption).
         */
        fun onFocusGained()
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Retained so it can be abandoned later on API 26+. */
    private var focusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "[FOCUS] AudioManager event: $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN                          -> listener.onFocusGained()
            AudioManager.AUDIOFOCUS_LOSS                          -> listener.onFocusLost()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK       -> listener.onFocusLostTransient()
        }
    }

    companion object {
        private const val TAG = "AudioFocusCoordinator"
    }

    /**
     * Requests AUDIOFOCUS_GAIN for music playback.
     *
     * Uses [AudioFocusRequest] (API 26+) or the legacy overload below that.
     * Sets [AudioAttributes] with USAGE_MEDIA + CONTENT_TYPE_MUSIC so the system
     * can make smart routing decisions (e.g. muting Bluetooth headset mic).
     *
     * @return true if focus was granted immediately. CrossfadeEngine should start
     *         playback regardless and handle subsequent changes via [Listener].
     */
    fun request(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
                .also { focusRequest = it }
            val result = audioManager.requestAudioFocus(req)
            Log.i(TAG, "[FOCUS] Request result=$result")
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            Log.i(TAG, "[FOCUS] Legacy request result=$result")
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * Releases audio focus. MUST be called from [CrossfadeEngine.release] so the
     * system knows this app is done producing audio and other apps can regain focus.
     */
    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                focusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        Log.i(TAG, "[FOCUS] Focus abandoned")
    }
}