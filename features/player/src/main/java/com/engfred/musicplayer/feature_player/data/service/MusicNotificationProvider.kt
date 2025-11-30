package com.engfred.musicplayer.feature_player.data.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import javax.inject.Inject

/**
 * Custom MediaNotification.Provider for Media3 to manage music playback notifications.
 * This class handles creating and updating the notification.
 *
 * Note: DefaultMediaNotificationProvider automatically handles loading artwork from
 * MediaItem.mediaMetadata.artworkUri. We do not need to override a specific method
 * for bitmap loading unless we want to provide a completely custom notification layout
 * or use a different image loading mechanism (which would involve implementing
 * MediaSessionService.MediaNotification.Provider directly).
 *
 * By simply extending DefaultMediaNotificationProvider, it will use the artworkUri
 * you've already set in your MediaItem.
 */
@UnstableApi
class MusicNotificationProvider @OptIn(UnstableApi::class)
@Inject constructor(
    private val context: Context
    // Removed: private val imageLoader: ImageLoader // No longer directly used here for bitmap override
) : DefaultMediaNotificationProvider(context) {

    // No need for a custom getMediaItemBitmap override.
    // DefaultMediaNotificationProvider handles artwork loading from MediaItem.mediaMetadata.artworkUri.

    // You can override other methods here to customize the notification further,
    // e.g., get, get, or getNotificationCardView if you need to change the layout or actions.
    // For now, DefaultMediaNotificationProvider handles most of the heavy lifting.
}