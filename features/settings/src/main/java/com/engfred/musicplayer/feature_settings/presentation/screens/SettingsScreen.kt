package com.engfred.musicplayer.feature_settings.presentation.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.feature_settings.presentation.components.AppVersionSection
import com.engfred.musicplayer.feature_settings.presentation.components.ContactDeveloperSection
import com.engfred.musicplayer.feature_settings.presentation.components.FlatToggleRow
import com.engfred.musicplayer.feature_settings.presentation.components.PresetSelectionBottomSheet
import com.engfred.musicplayer.feature_settings.presentation.components.SettingsActionRow
import com.engfred.musicplayer.feature_settings.presentation.components.SettingsSectionGroup
import com.engfred.musicplayer.feature_settings.presentation.components.ThemeSelectionBottomSheet
import com.engfred.musicplayer.feature_settings.presentation.components.UpdateAvailableBanner
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsEvent
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsViewModel

const val DEVELOPER_PHONE_LOCAL = "+256754348118"
const val DEVELOPER_WHATSAPP_NUMBER = "256754348118"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToDuplicates: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showThemeBottomSheet by remember { mutableStateOf(false) }
    var showPresetBottomSheet by remember { mutableStateOf(false) }

    val themeSheetState = rememberModalBottomSheetState()
    val presetSheetState = rememberModalBottomSheetState()

    val currentThemeName = remember(uiState.selectedTheme) {
        uiState.selectedTheme.name.replace("_", " ").lowercase().replaceFirstChar { it.titlecase() }
    }

    val currentPresetName = remember(uiState.audioPreset) {
        uiState.audioPreset.name.replace("_", " ").lowercase().replaceFirstChar { it.titlecase() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        if (uiState.error != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
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
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    UpdateAvailableBanner(
                        updateInfo = info,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, info.downloadUrl.toUri())
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }

        // ── 1. Appearance Section ─────────────────────────────────────────
        SettingsSectionGroup(title = "Appearance") {
            SettingsActionRow(
                title = "App Theme",
                subtitle = "Choose a look that suits you best",
                icon = Icons.Rounded.Brush,
                valueText = currentThemeName,
                onClick = { showThemeBottomSheet = true }
            )
        }

        // ── 2. Library & Playback Section ─────────────────────────────────
        SettingsSectionGroup(title = "Library & Playback") {
            FlatToggleRow(
                title = "Audio File Types",
                subtitle = "Turn off to show only MP3 files in library.",
                icon = Icons.Rounded.AudioFile,
                isChecked = uiState.audioFileTypeFilter == AudioFileTypeFilter.ALL,
                onCheckedChange = { isChecked ->
                    val newFilter = if (isChecked) AudioFileTypeFilter.ALL else AudioFileTypeFilter.MP3_ONLY
                    viewModel.onEvent(SettingsEvent.UpdateAudioFileTypeFilter(newFilter))
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            FlatToggleRow(
                title = "Widget Background",
                subtitle = "Follow system light/dark mode for widget.",
                icon = Icons.Rounded.Widgets,
                isChecked = uiState.widgetBackgroundMode == WidgetBackgroundMode.THEME_AWARE,
                onCheckedChange = { isChecked ->
                    val newMode = if (isChecked) WidgetBackgroundMode.THEME_AWARE else WidgetBackgroundMode.STATIC
                    viewModel.onEvent(SettingsEvent.UpdateWidgetBackgroundMode(newMode, context))
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsActionRow(
                title = "Find Duplicates",
                subtitle = "Safely remove duplicate audio files.",
                icon = Icons.Rounded.CleaningServices,
                onClick = onNavigateToDuplicates
            )
        }

        // ── 3. Audio Equalizer Section ────────────────────────────────────
        SettingsSectionGroup(title = "Equalizer") {
            SettingsActionRow(
                title = "Audio Preset",
                subtitle = "Tune frequency playback profile",
                icon = Icons.Rounded.Equalizer,
                valueText = currentPresetName,
                onClick = { showPresetBottomSheet = true }
            )
        }

        // ── 4. Connect & Support Section ──────────────────────────────────
        SettingsSectionGroup(title = "Connect & Support") {
            ContactDeveloperSection(
                onWhatsApp = {
                    val uri = "https://wa.me/$DEVELOPER_WHATSAPP_NUMBER".toUri()
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.whatsapp")
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
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
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 5. About & Copyright (Cleanly at the very bottom) ──────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        ) {
            AppVersionSection(
                copyrightText = "© 2026 Engineer Fred"
            )
        }
    }

    // Theme Selection Bottom Sheet
    if (showThemeBottomSheet) {
        ThemeSelectionBottomSheet(
            onDismissRequest = { showThemeBottomSheet = false },
            sheetState = themeSheetState,
            selectedTheme = uiState.selectedTheme,
            onThemeSelected = { viewModel.onEvent(SettingsEvent.UpdateTheme(it)) }
        )
    }

    // Audio Preset Selection Bottom Sheet
    if (showPresetBottomSheet) {
        PresetSelectionBottomSheet(
            onDismissRequest = { showPresetBottomSheet = false },
            sheetState = presetSheetState,
            selectedPreset = uiState.audioPreset,
            onPresetSelected = { viewModel.onEvent(SettingsEvent.UpdateAudioPreset(it)) }
        )
    }
}
