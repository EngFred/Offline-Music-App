package com.engfred.musicplayer.feature_video.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose-native subtitle rendering overlay.
 * Renders subtitle text from the playback state.
 * This approach gives full control over subtitle styling to match the app's design.
 */
@Composable
fun SubtitleOverlay(
    subtitleText: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    backgroundColor: Color = Color.Black.copy(alpha = 0.5f),
    fontSize: Float = 16f,
    fontWeight: FontWeight = FontWeight.Normal
) {
    if (subtitleText.isBlank()) return

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = subtitleText,
            color = textColor,
            fontSize = fontSize.sp,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            lineHeight = (fontSize * 1.4).sp
        )
    }
}

/**
 * Enhanced subtitle overlay with configurable styling options.
 */
@Composable
fun EnhancedSubtitleOverlay(
    subtitleText: String,
    modifier: Modifier = Modifier,
    subtitleStyle: SubtitleStyle = SubtitleStyle()
) {
    if (subtitleText.isBlank()) return

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = subtitleText,
            color = subtitleStyle.textColor,
            fontSize = subtitleStyle.fontSize.sp,
            fontWeight = subtitleStyle.fontWeight,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(
                    horizontal = subtitleStyle.horizontalPadding.dp,
                    vertical = subtitleStyle.verticalPadding.dp
                )
                .clip(RoundedCornerShape(subtitleStyle.cornerRadius.dp))
                .background(subtitleStyle.backgroundColor)
                .padding(
                    horizontal = subtitleStyle.textHorizontalPadding.dp,
                    vertical = subtitleStyle.textVerticalPadding.dp
                ),
            lineHeight = (subtitleStyle.fontSize * subtitleStyle.lineHeightMultiplier).sp
        )
    }
}

/**
 * Configuration for subtitle styling.
 */
data class SubtitleStyle(
    val textColor: Color = Color.White,
    val backgroundColor: Color = Color.Black.copy(alpha = 0.5f),
    val fontSize: Float = 16f,
    val fontWeight: FontWeight = FontWeight.Normal,
    val cornerRadius: Float = 8f,
    val horizontalPadding: Float = 32f,
    val verticalPadding: Float = 24f,
    val textHorizontalPadding: Float = 16f,
    val textVerticalPadding: Float = 8f,
    val lineHeightMultiplier: Float = 1.4f
) {
    companion object {
        fun small() = SubtitleStyle(
            fontSize = 14f,
            fontWeight = FontWeight.Medium
        )

        fun medium() = SubtitleStyle(
            fontSize = 16f,
            fontWeight = FontWeight.Normal
        )

        fun large() = SubtitleStyle(
            fontSize = 20f,
            fontWeight = FontWeight.Medium
        )

        fun extraLarge() = SubtitleStyle(
            fontSize = 24f,
            fontWeight = FontWeight.Bold
        )
    }
}