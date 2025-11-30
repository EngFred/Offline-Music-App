package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun FavoriteParticleEffect(
    modifier: Modifier = Modifier,
    isFavorite: Boolean
) {
    var showParticles by remember { mutableStateOf(false) }
    var prevIsFavorite by remember { mutableStateOf(isFavorite) }

    LaunchedEffect(isFavorite) {
        if (isFavorite && !prevIsFavorite) {
            showParticles = true
        }
        prevIsFavorite = isFavorite
    }

    Box(modifier = modifier) {
        if (showParticles) {
            (0..4).forEach { i ->
                Particle(i)
            }
            LaunchedEffect(Unit) {
                delay(1000)
                showParticles = false
            }
        }
    }
}

@Composable
private fun Particle(index: Int) {
    val tint = Color(0xFFE91E63)
    val randomXTarget = remember { (Random.nextFloat() - 0.5f) * 100f }
    val randomYTarget = remember { -(100f + Random.nextFloat() * 100f) }
    val randomDelay = remember { Random.nextInt(0, 200) }

    val alphaAnim = remember { Animatable(1f) }
    val xAnim = remember { Animatable(0f) }
    val yAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    LaunchedEffect(Unit) {
        alphaAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 800, delayMillis = randomDelay)
        )
    }

    LaunchedEffect(Unit) {
        xAnim.animateTo(
            targetValue = randomXTarget,
            animationSpec = tween(durationMillis = 800, delayMillis = randomDelay)
        )
    }

    LaunchedEffect(Unit) {
        yAnim.animateTo(
            targetValue = randomYTarget,
            animationSpec = tween(durationMillis = 800, delayMillis = randomDelay)
        )
    }

    Icon(
        imageVector = Icons.Rounded.Favorite,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .offset(xAnim.value.dp, yAnim.value.dp)
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                alpha = alphaAnim.value
            }
            .size(24.dp)
    )
}