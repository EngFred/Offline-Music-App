package com.engfred.musicplayer.feature_player.data.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.WidgetDisplayInfo
import com.engfred.musicplayer.core.util.MediaUtils
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "WidgetUpdater"
@SuppressLint("UseKtx")
@OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.P)
object WidgetUpdater {

    // Main thread handler for scheduling updates.
    private val handler = Handler(Looper.getMainLooper())

    // Keep most recent request params
    private data class Req(
        val contextAppPackage: String,
        val contextAppName: String, // not strictly required - for debugging if needed
        val exoPlayer: Player?, // nullable so callers can update without a running player
        val idleDisplayInfo: WidgetDisplayInfo?,
        val idleRepeatMode: Int,
        val useThemeAware: Boolean,
        val isInitial: Boolean = false  // For full vs partial
    )

    private val lastReqRef = AtomicReference<Req?>(null)
    private var lastUpdateRunnable: Runnable? = null  // NEW: For debounce

    // Keep last known positive duration so we don't flash 00:00 while the player warms up.
    private var lastKnownDurationMs: Long = 0L
    private const val UNKNOWN_DURATION_TEXT = "00:00"
    private const val DEFAULT_TITLE = "Music Player"
    private const val DEFAULT_ARTIST = "Unknown Artist"
    private var wasFullShown: Boolean = false  //Global flag to prevent idle reversion

    fun updateWidget(
        context: Context,
        exoPlayer: Player?, // nullable so callers can update without a running player
        idleDisplayInfo: WidgetDisplayInfo? = null,
        idleRepeatMode: Int = Player.REPEAT_MODE_OFF,
        useThemeAware: Boolean = false, // whether to adapt to system theme
        isInitial: Boolean = false
    ) {
        try {
            // Build a compact request and store as last.
            val req = Req(
                contextAppPackage = context.applicationContext.packageName,
                contextAppName = context.applicationContext.javaClass.simpleName,
                exoPlayer = exoPlayer,
                idleDisplayInfo = idleDisplayInfo,
                idleRepeatMode = idleRepeatMode,
                useThemeAware = useThemeAware,
                isInitial = isInitial
            )

            // store latest request (kept for debugging / fallback)
            lastReqRef.set(req)

            // Debounce - remove previous and post new
            lastUpdateRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                try {
                    val last = lastReqRef.getAndSet(null) ?: req
                    performUpdate(context.applicationContext, last)
                } catch (t: Throwable) {
                    Log.w(TAG, "Scheduled widget update failed: ${t.message}")
                }
            }
            lastUpdateRunnable = runnable
            handler.postDelayed(runnable, if (isInitial) 0 else 100)  // Slight delay for non-initial to batch
        } catch (e: Exception) {
            Log.w(TAG, "updateWidget enqueue failed: ${e.message}")
        }
    }

    /**
     * Actual heavy-lifting update executed on main thread.
     * Uses the request snapshot captured earlier.
     */
    private fun performUpdate(appContext: Context, req: Req) {
        try {
            val context = appContext
            val providerComponent = ComponentName(
                context.packageName,
                PlaybackService.WIDGET_PROVIDER_CLASS
            )
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(providerComponent)
            if (ids.isEmpty()) return

            val resources = context.resources
            val layoutId = resources.getIdentifier("widget_layout", "layout", context.packageName)
            if (layoutId == 0) return

            val current = req.exoPlayer?.currentMediaItem
            val isIdle = (req.exoPlayer == null) || (current == null)
            var showFullInfo = !isIdle || (req.idleDisplayInfo != null)

            //Prevent reversion to idle if full was shown
            if (!showFullInfo && wasFullShown) {
                Log.d(TAG, "Skipping idle update as full was already shown")
                return
            }

            val idRoot = resources.getIdentifier("widget_root", "id", context.packageName)
            val idIdleContainer = resources.getIdentifier("idle_container", "id", context.packageName)
            val idFullContainer = resources.getIdentifier("full_container", "id", context.packageName)
            val idPlayPauseIdle = resources.getIdentifier("widget_play_pause_idle", "id", context.packageName)
            val idPlayPauseFull = resources.getIdentifier("widget_play_pause_full", "id", context.packageName)
            val idNext = resources.getIdentifier("widget_next", "id", context.packageName)
            val idPrev = resources.getIdentifier("widget_prev", "id", context.packageName)
            val idRepeat = resources.getIdentifier("widget_repeat", "id", context.packageName)
            val idShuffle = resources.getIdentifier("widget_shuffle", "id", context.packageName)
            val idAlbumArt = resources.getIdentifier("widget_album_art", "id", context.packageName)
            val idTitle = resources.getIdentifier("widget_title", "id", context.packageName)
            val idArtist = resources.getIdentifier("widget_artist", "id", context.packageName)
            val idDuration = resources.getIdentifier("widget_duration", "id", context.packageName)

            // System dark flag
            val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            val bgResToUse: Int = if (req.useThemeAware) {
                resources.getIdentifier(
                    if (isSystemDark) "widget_background_dark" else "widget_background_light",
                    "drawable",
                    context.packageName
                ).takeIf { it != 0 } ?: resources.getIdentifier("widget_background", "drawable", context.packageName)
            } else {
                resources.getIdentifier("widget_background", "drawable", context.packageName)
            }

            // icon/text tints for theme-aware
            val defaultIconTint = if (req.useThemeAware) {
                if (isSystemDark) Color.WHITE else Color.BLACK
            } else Color.WHITE

            val textColorPrimary = if (req.useThemeAware) {
                if (isSystemDark) Color.WHITE else Color.BLACK
            } else Color.WHITE

            val textColorSecondary = if (req.useThemeAware) {
                if (isSystemDark) Color.LTGRAY else Color.DKGRAY
            } else 0xFFCCCCCC.toInt()

            ids.forEach { appWidgetId ->
                try {
                    val partialViews = RemoteViews(context.packageName, layoutId)
                    val usePartial = !req.isInitial  // Partial for non-initial to minimize redraw

                    if (idRoot != 0 && bgResToUse != 0) partialViews.setInt(idRoot, "setBackgroundResource", bgResToUse)

                    if (!showFullInfo) {
                        // Idle mode: show idle container, hide full
                        if (idIdleContainer != 0) partialViews.setViewVisibility(idIdleContainer, View.VISIBLE)
                        if (idFullContainer != 0) partialViews.setViewVisibility(idFullContainer, View.GONE)

                        val playDrawableId = resources.getIdentifier("ic_play_arrow_24", "drawable", context.packageName)
                        if (idPlayPauseIdle != 0 && playDrawableId != 0) {
                            partialViews.setImageViewResource(idPlayPauseIdle, playDrawableId)
                            partialViews.setInt(idPlayPauseIdle, "setColorFilter", Color.BLACK)
                        }
                        wasFullShown = false  // Reset flag
                    } else {
                        // Full mode: show full container, hide idle
                        if (idIdleContainer != 0) partialViews.setViewVisibility(idIdleContainer, View.GONE)
                        if (idFullContainer != 0) partialViews.setViewVisibility(idFullContainer, View.VISIBLE)

                        // Show full info (use player if active, else idle info)
                        val metadata: androidx.media3.common.MediaMetadata? = if (!isIdle) current?.mediaMetadata else null
                        val title = if (!isIdle) (metadata?.title?.toString() ?: DEFAULT_TITLE) else req.idleDisplayInfo!!.title
                        val artist = if (!isIdle) (metadata?.artist?.toString() ?: DEFAULT_ARTIST) else req.idleDisplayInfo!!.artist

                        val currentPositionMs = if (!isIdle) req.exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L else req.idleDisplayInfo!!.positionMs

                        val candidateDurationMs = if (!isIdle) (req.exoPlayer?.duration ?: 0L) else req.idleDisplayInfo!!.durationMs

                        val totalDurationMs = when {
                            candidateDurationMs > 0L -> {
                                lastKnownDurationMs = candidateDurationMs
                                candidateDurationMs
                            }
                            lastKnownDurationMs > 0L -> lastKnownDurationMs
                            else -> 0L
                        }

                        val totalDurationText = if (totalDurationMs > 0L) MediaUtils.formatDuration(totalDurationMs) else UNKNOWN_DURATION_TEXT
                        val durationText = "${MediaUtils.formatDuration(currentPositionMs)} / $totalDurationText"

                        if (idTitle != 0) {
                            partialViews.setTextViewText(idTitle, title)
                            partialViews.setTextColor(idTitle, textColorPrimary)
                        }
                        if (idArtist != 0) {
                            partialViews.setTextViewText(idArtist, artist)
                            partialViews.setTextColor(idArtist, textColorSecondary)
                        }
                        if (idDuration != 0) {
                            partialViews.setTextViewText(idDuration, durationText)
                            partialViews.setTextColor(idDuration, textColorSecondary)
                        }

                        val isPlaying = req.exoPlayer?.isPlaying ?: false
                        val playDrawableResName = if (isPlaying) "ic_pause_24" else "ic_play_arrow_24"
                        val playDrawableId = resources.getIdentifier(playDrawableResName, "drawable", context.packageName)
                        if (idPlayPauseFull != 0 && playDrawableId != 0) {
                            partialViews.setImageViewResource(idPlayPauseFull, playDrawableId)
                            partialViews.setInt(idPlayPauseFull, "setColorFilter", defaultIconTint)
                        }

                        // load artwork
                        val artworkUri: Uri? = if (!isIdle) metadata?.artworkUri else req.idleDisplayInfo!!.artworkUri
                        var artBitmap = tryLoadArtwork(context, artworkUri)
                        if (artBitmap == null) {
                            val defaultId = resources.getIdentifier("ic_music_note_24", "drawable", context.packageName)
                            if (defaultId != 0) {
                                val tintColor = if (req.useThemeAware) {
                                    if (isSystemDark) Color.WHITE else Color.BLACK
                                } else Color.WHITE
                                artBitmap = getTintedBitmap(context, defaultId, tintColor)
                            }
                        }
                        if (artBitmap != null && idAlbumArt != 0) {
                            val circular = createCircularBitmap(artBitmap)
                            partialViews.setImageViewBitmap(idAlbumArt, circular)
                        }

                        // tint next/prev icons
                        if (idPrev != 0) partialViews.setInt(idPrev, "setColorFilter", defaultIconTint)
                        if (idNext != 0) partialViews.setInt(idNext, "setColorFilter", defaultIconTint)

                        val repeatMode = if (!isIdle) req.exoPlayer?.repeatMode ?: Player.REPEAT_MODE_OFF else req.idleRepeatMode
                        val repeatDrawableResName = if (repeatMode == Player.REPEAT_MODE_ONE) "repeat_once" else "repeat"
                        var repeatDrawableId = resources.getIdentifier(repeatDrawableResName, "drawable", context.packageName)
                        if (repeatDrawableId == 0) {
                            repeatDrawableId = resources.getIdentifier(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> "ic_repeat_one_24"
                                    Player.REPEAT_MODE_ALL -> "ic_repeat_on_24"
                                    else -> "ic_repeat_24"
                                }, "drawable", context.packageName
                            )
                        }
                        if (idRepeat != 0 && repeatDrawableId != 0) {
                            partialViews.setImageViewResource(idRepeat, repeatDrawableId)
                            val tintColor = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Color.GRAY
                                else -> defaultIconTint
                            }
                            partialViews.setInt(idRepeat, "setColorFilter", tintColor)
                        }

                        val shuffleEnabled = if (!isIdle) req.exoPlayer?.shuffleModeEnabled ?: false else false
                        val shuffleDrawableId = resources.getIdentifier("ic_shuffle_24", "drawable", context.packageName)
                        if (idShuffle != 0 && shuffleDrawableId != 0) {
                            partialViews.setImageViewResource(idShuffle, shuffleDrawableId)
                            val shuffleTint = if (shuffleEnabled) defaultIconTint else Color.GRAY
                            partialViews.setInt(idShuffle, "setColorFilter", shuffleTint)
                        }
                        wasFullShown = true  // Set flag
                    }

                    val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)

                    if (minWidth < 250) {
                        if (idPrev != 0) partialViews.setViewVisibility(idPrev, View.GONE)
                        if (idArtist != 0) partialViews.setViewVisibility(idArtist, View.GONE)
                    } else {
                        if (idPrev != 0) partialViews.setViewVisibility(idPrev, View.VISIBLE)
                        if (idArtist != 0) partialViews.setViewVisibility(idArtist, View.VISIBLE)
                    }

                    // Set PendingIntents for clicks (copied from buildRemoteViews)
                    fun pendingIntentFor(action: String, widgetId: Int): PendingIntent {
                        val i = Intent().apply {
                            component = ComponentName(context.packageName, PlaybackService.WIDGET_PROVIDER_CLASS)
                            this.action = action
                            data = "app://widget/$action/$widgetId".toUri()
                            `package` = context.packageName
                        }
                        val requestCode = (action.hashCode() xor widgetId)
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        return PendingIntent.getBroadcast(context, requestCode, i, flags)
                    }

                    partialViews.setOnClickPendingIntent(idPlayPauseIdle, pendingIntentFor(PlaybackService.ACTION_WIDGET_PLAY_PAUSE, appWidgetId))
                    partialViews.setOnClickPendingIntent(idPlayPauseFull, pendingIntentFor(PlaybackService.ACTION_WIDGET_PLAY_PAUSE, appWidgetId))
                    partialViews.setOnClickPendingIntent(idNext, pendingIntentFor(PlaybackService.ACTION_WIDGET_NEXT, appWidgetId))
                    partialViews.setOnClickPendingIntent(idPrev, pendingIntentFor(PlaybackService.ACTION_WIDGET_PREV, appWidgetId))
                    partialViews.setOnClickPendingIntent(idRepeat, pendingIntentFor(PlaybackService.ACTION_WIDGET_REPEAT, appWidgetId))
                    partialViews.setOnClickPendingIntent(idShuffle, pendingIntentFor(PlaybackService.ACTION_WIDGET_SHUFFLE, appWidgetId))

                    val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    openAppIntent?.let {
                        it.data = "app://widget/open/$appWidgetId".toUri()
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        val openPending = PendingIntent.getActivity(context, appWidgetId, it, flags)
                        partialViews.setOnClickPendingIntent(idAlbumArt, openPending)
                        partialViews.setOnClickPendingIntent(idTitle, openPending)
                        partialViews.setOnClickPendingIntent(idArtist, openPending)
                    }

                    if (usePartial) {
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, partialViews)
                    } else {
                        appWidgetManager.updateAppWidget(appWidgetId, partialViews)
                    }
                } catch (_: Throwable) {
                    // ignore per-widget failures (do not crash service)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "performUpdate throwable: ${e.message}")
        }
    }

    private fun tryLoadArtwork(context: Context, uri: Uri?): Bitmap? {
        if (uri == null) return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = bitmap.width.coerceAtMost(bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    private fun getTintedBitmap(context: Context, drawableId: Int, tintColor: Int): Bitmap? {
        val drawable: Drawable? = ContextCompat.getDrawable(context, drawableId)
        drawable?.let {
            val wrapped = DrawableCompat.wrap(it.mutate())
            DrawableCompat.setTint(wrapped, tintColor)
            wrapped.setBounds(0, 0, wrapped.intrinsicWidth, wrapped.intrinsicHeight)
            val bitmap = Bitmap.createBitmap(wrapped.intrinsicWidth, wrapped.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wrapped.draw(canvas)
            return bitmap
        }
        return null
    }
}