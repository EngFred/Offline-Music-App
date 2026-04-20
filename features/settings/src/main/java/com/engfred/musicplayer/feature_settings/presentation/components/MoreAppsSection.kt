package com.engfred.musicplayer.feature_settings.presentation.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_settings.domain.model.AppInfo
import kotlin.collections.forEach

/**
 * "More apps from the developer" section.
 *
 * Each row taps through to [AppInfo.releasesUrl] — the GitHub releases page
 * for that app — so users can download the latest APK directly, mirroring the
 * same flow used by the in-app update checker.
 *
 * The section header uses the same icon + title + subtitle layout as
 * [SettingsSection] to keep the page visually consistent.
 */
@Composable
fun MoreAppsSection(apps: List<AppInfo>) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Section header ────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "More Apps from the Developer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Check out other apps by Engineer Fred",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── App rows ──────────────────────────────────────────────────────────
        apps.forEach { app ->
            AppRow(
                app = app,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(app.releasesUrl))
                    context.startActivity(intent)
                }
            )
        }
    }
}