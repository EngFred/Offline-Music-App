package com.engfred.musicplayer.feature_settings.presentation.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.net.toUri
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.UpdateInfo
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import com.engfred.musicplayer.feature_settings.presentation.components.AppVersionSection
import com.engfred.musicplayer.feature_settings.presentation.components.SettingsSection
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsEvent
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsViewModel

// ── Developer App catalogue ───────────────────────────────────────────────────
// Replace the placeholder entries below with your real app names, descriptions,
// and GitHub repo paths (everything after "https://github.com/EngFred/").
// Users are taken to the GitHub releases page for each app so they can download
// the latest APK directly — the same flow your in-app update checker uses.
//
// Format: AppInfo(emoji, "Display Name", "Short tagline", "RepoName")
// ─────────────────────────────────────────────────────────────────────────────
private val DEVELOPER_APPS = listOf(
    AppInfo(
        emoji       = "🎵",
        name        = "Offline Music Player",
        description = "The app you are using right now.",
        repoSlug    = "Offline-Music-App"
    ),
    // ── TODO: replace the four entries below with your real repos ─────────────
    AppInfo(
        emoji       = "📖",
        name        = "App Two",
        description = "Short description of what this app does.",
        repoSlug    = "your-repo-name-here"
    ),
    AppInfo(
        emoji       = "🏋️",
        name        = "App Three",
        description = "Short description of what this app does.",
        repoSlug    = "your-repo-name-here"
    ),
    AppInfo(
        emoji       = "🌍",
        name        = "App Four",
        description = "Short description of what this app does.",
        repoSlug    = "your-repo-name-here"
    ),
    AppInfo(
        emoji       = "🔒",
        name        = "App Five",
        description = "Short description of what this app does.",
        repoSlug    = "your-repo-name-here"
    ),
)

/** Lightweight model for the "More Apps" catalogue. */
data class AppInfo(
    val emoji: String,
    val name: String,
    val description: String,
    /**
     * The repository slug (the part after "https://github.com/EngFred/").
     * Used to build the GitHub releases URL the user is sent to on tap.
     */
    val repoSlug: String
) {
    /** Full URL to the latest release of this app on GitHub. */
    val releasesUrl: String
        get() = "https://github.com/EngFred/$repoSlug/releases/latest"
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToDuplicates: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = innerPadding.calculateTopPadding() + 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            if (uiState.error != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "Error: ${uiState.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = uiState.updateInfo != null,
                enter = fadeIn() + expandVertically()
            ) {
                uiState.updateInfo?.let { info ->
                    UpdateAvailableBanner(
                        updateInfo = info,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, info.downloadUrl.toUri())
                            context.startActivity(intent)
                        }
                    )
                }
            }

            // ── Theme Section ─────────────────────────────────────────────────
            SettingsSection(
                title = "App Theme",
                subtitle = "Choose a look that suits you",
                icon = Icons.Rounded.Brush,
                items = AppThemeType.entries,
                selectedItem = uiState.selectedTheme,
                displayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } },
                onSelect = { viewModel.onEvent(SettingsEvent.UpdateTheme(it)) }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // ── Preferences / Toggles Section ─────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                FlatToggleRow(
                    title = "Audio File Types",
                    subtitle = "Turn off to show only MP3 files in your library.",
                    icon = Icons.Rounded.AudioFile,
                    isChecked = uiState.audioFileTypeFilter == AudioFileTypeFilter.ALL,
                    onCheckedChange = { isChecked ->
                        val newFilter = if (isChecked) AudioFileTypeFilter.ALL else AudioFileTypeFilter.MP3_ONLY
                        viewModel.onEvent(SettingsEvent.UpdateAudioFileTypeFilter(newFilter))
                    }
                )

                FlatToggleRow(
                    title = "Widget Background",
                    subtitle = "Let the home screen widget follow system light/dark mode.",
                    icon = Icons.Rounded.Widgets,
                    isChecked = uiState.widgetBackgroundMode == WidgetBackgroundMode.THEME_AWARE,
                    onCheckedChange = { isChecked ->
                        val newMode = if (isChecked) WidgetBackgroundMode.THEME_AWARE else WidgetBackgroundMode.STATIC
                        viewModel.onEvent(SettingsEvent.UpdateWidgetBackgroundMode(newMode, context))
                    }
                )

                FlatToggleRow(
                    title = "Mix of the Day — Short Tracks Only",
                    subtitle = "Limit daily mixes to tracks under 5 minutes for tighter, faster-paced sets.",
                    icon = Icons.Rounded.HourglassTop,
                    isChecked = uiState.mixOfTheDayFilterByDuration,
                    onCheckedChange = { isChecked ->
                        viewModel.onEvent(SettingsEvent.UpdateMixOfTheDayFilterByDuration(isChecked))
                    }
                )

                SettingsActionRow(
                    title = "Find Duplicates",
                    subtitle = "Free up space by safely removing identical audio files.",
                    icon = Icons.Rounded.CleaningServices,
                    onClick = onNavigateToDuplicates
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // ── Audio Preset Section ──────────────────────────────────────────
            SettingsSection(
                title = "Audio Preset",
                subtitle = "Select an equalizer preset for playback",
                icon = Icons.Rounded.Equalizer,
                items = AudioPreset.entries,
                selectedItem = uiState.audioPreset,
                displayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } },
                onSelect = { viewModel.onEvent(SettingsEvent.UpdateAudioPreset(it)) }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // ── More Apps from the Developer ──────────────────────────────────
            MoreAppsSection(apps = DEVELOPER_APPS)

            Spacer(modifier = Modifier.weight(1f, fill = false).height(32.dp))

            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                AppVersionSection(
                    copyrightText = "© 2026 Engineer Fred",
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// MORE APPS SECTION
// ═════════════════════════════════════════════════════════════════════════════

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
private fun MoreAppsSection(apps: List<AppInfo>) {
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

/**
 * Single app row inside [MoreAppsSection].
 *
 * Layout mirrors [SettingsActionRow] so the section feels native to the rest
 * of the settings page, with the addition of:
 *  • A circular emoji badge on the left (no external images needed).
 *  • An [OpenInNew] icon on the right to signal the link opens outside the app.
 */
@Composable
private fun AppRow(
    app: AppInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular emoji badge — no network call, no image loading
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.emoji,
                style = MaterialTheme.typography.titleLarge
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = app.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // "Opens externally" icon — more descriptive than a plain chevron
        Icon(
            imageVector = Icons.Rounded.OpenInNew,
            contentDescription = "View on GitHub",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// EXISTING PRIVATE COMPOSABLES (unchanged)
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun UpdateAvailableBanner(
    updateInfo: UpdateInfo,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = "Update Available",
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Update Available",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Version ${updateInfo.latestVersion} is ready to download.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.width(16.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun FlatToggleRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = "Navigate",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}