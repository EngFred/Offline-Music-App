package com.engfred.musicplayer.feature_player.presentation.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * A Jetpack Compose wrapper around [MediaRouteButton] configured for Google Cast.
 *
 * MediaRouteButton requires the context to be a FragmentActivity (it shows a DialogFragment).
 * AndroidView's factory receives a plain Context, so we walk up the context chain to find the
 * host Activity and use that instead.
 */
@Composable
fun CastMediaRouteButton(
    modifier: Modifier = Modifier,
    tintColor: Color? = null
) {
    AndroidView(
        factory = { context ->
            val activityContext = context.findActivity() ?: context
            MediaRouteButton(activityContext).apply {
                try {
                    CastButtonFactory.setUpMediaRouteButton(activityContext, this)
                } catch (_: Exception) {
                    // Gracefully fallback if CastContext is unavailable
                }
                setAlwaysVisible(true)
            }
        },
        modifier = modifier.size(36.dp)
    )
}

/**
 * Walks up the [ContextWrapper] chain to find the hosting [Activity].
 */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
