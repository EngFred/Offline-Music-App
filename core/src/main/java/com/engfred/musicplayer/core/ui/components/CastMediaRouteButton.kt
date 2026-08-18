package com.engfred.musicplayer.core.ui.components

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
                } catch (_: Exception) {}
                setAlwaysVisible(true)
            }
        },
        modifier = modifier.size(36.dp)
    )
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
