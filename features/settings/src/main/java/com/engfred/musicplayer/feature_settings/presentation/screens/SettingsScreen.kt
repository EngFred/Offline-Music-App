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
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SupportAgent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.engfred.musicplayer.feature_settings.presentation.components.ContactDeveloperSection
import com.engfred.musicplayer.feature_settings.presentation.components.FlatToggleRow
import com.engfred.musicplayer.feature_settings.presentation.components.SettingsActionRow
import com.engfred.musicplayer.feature_settings.presentation.components.SettingsSection
import com.engfred.musicplayer.feature_settings.presentation.components.UpdateAvailableBanner
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
//private val DEVELOPER_APPS = listOf(
//    AppInfo(
//        emoji       = "🎵",
//        name        = "Offline Music Player",
//        description = "The app you are using right now.",
//        repoSlug    = "Offline-Music-App"
//    ),
//    // ── TODO: replace the four entries below with your real repos ─────────────
//    AppInfo(
//        emoji       = "📖",
//        name        = "App Two",
//        description = "Short description of what this app does.",
//        repoSlug    = "your-repo-name-here"
//    ),
//    AppInfo(
//        emoji       = "🏋️",
//        name        = "App Three",
//        description = "Short description of what this app does.",
//        repoSlug    = "your-repo-name-here"
//    ),
//    AppInfo(
//        emoji       = "🌍",
//        name        = "App Four",
//        description = "Short description of what this app does.",
//        repoSlug    = "your-repo-name-here"
//    ),
//    AppInfo(
//        emoji       = "🔒",
//        name        = "App Five",
//        description = "Short description of what this app does.",
//        repoSlug    = "your-repo-name-here"
//    ),
//)

// ── Developer contact details ─────────────────────────────────────────────────
// International format is required for WhatsApp deep-links (wa.me).
// The local number 0754348118 maps to +256754348118 (Uganda, +256).
// ─────────────────────────────────────────────────────────────────────────────
const val DEVELOPER_PHONE_LOCAL        = "+256754348118"
const val DEVELOPER_WHATSAPP_NUMBER    = "256754348118"   // no leading +



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

//            // ── More Apps from the Developer ──────────────────────────────────
//            MoreAppsSection(apps = DEVELOPER_APPS)

            // ── Contact the Developer ─────────────────────────────────────────
            ContactDeveloperSection(
                onWhatsApp = {
                    // Opens a direct WhatsApp chat with the developer — no need to
                    // save the number first. Falls back to the Play Store / browser
                    // if WhatsApp is not installed.
                    val uri = "https://wa.me/$DEVELOPER_WHATSAPP_NUMBER".toUri()
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.whatsapp")
                    }
                    // If WhatsApp is not installed, fall back to the browser deep-link
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                        )
                    }
                },
                onCall = {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = "tel:$DEVELOPER_PHONE_LOCAL".toUri()
                    }
                    context.startActivity(intent)
                },
                onSms = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = "smsto:$DEVELOPER_PHONE_LOCAL".toUri()
                    }
                    context.startActivity(intent)
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.weight(1f, fill = false).height(32.dp))

            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                AppVersionSection(
                    copyrightText = "© 2026 Engineer Fred",
                )
            }
        }
    }
}
