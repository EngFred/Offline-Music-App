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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Equalizer
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
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.UpdateInfo
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import com.engfred.musicplayer.feature_settings.presentation.components.AppVersionSection
import com.engfred.musicplayer.feature_settings.presentation.components.SettingsSection
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsEvent
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsViewModel
import androidx.core.net.toUri

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

            // Theme Section
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

            // Preferences / Toggles Section
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

            // Audio Preset Section
            SettingsSection(
                title = "Audio Preset",
                subtitle = "Select an equalizer preset for playback",
                icon = Icons.Rounded.Equalizer,
                items = AudioPreset.entries,
                selectedItem = uiState.audioPreset,
                displayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } },
                onSelect = { viewModel.onEvent(SettingsEvent.UpdateAudioPreset(it)) }
            )

            Spacer(modifier = Modifier.weight(1f, fill = false).height(32.dp))

            // App Version
            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                AppVersionSection(
                    copyrightText = "© 2026 Engineer Fred",
                )
            }
        }
    }
}

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