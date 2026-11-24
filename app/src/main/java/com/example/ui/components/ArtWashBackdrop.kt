package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.network.InnerTubeParsers
import com.example.model.LIKED_MUSIC_ID
import com.example.model.ON_REPEAT_ID

@Composable
fun ArtWashBackdrop(
    artworkUrl: String?,
    accentColor: Color?,
    modifier: Modifier = Modifier,
    useStageFallback: Boolean = true
) {
    val context = LocalContext.current
    val bg = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val animatedAccent by animateColorAsState(
        targetValue = accentColor ?: primary,
        animationSpec = tween(durationMillis = 220),
        label = "art_wash_accent"
    )
    val washBrush = remember(bg, animatedAccent) {
        val topWash = androidx.compose.ui.graphics.lerp(bg, animatedAccent, 0.22f).copy(alpha = 0.80f)
        Brush.verticalGradient(
            0.0f to topWash,
            0.48f to bg.copy(alpha = 0.90f),
            1.0f to bg
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer { }
            .background(bg)
    ) {
        if (!artworkUrl.isNullOrBlank()) {
            val bgRequest = remember(context, artworkUrl) {
                ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(48)
                    .allowHardware(true)
                    .crossfade(120)
                    .build()
            }
            AsyncImage(
                model = bgRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.26f)
            )
        } else if (useStageFallback) {
            val stageRequest = remember(context) {
                ImageRequest.Builder(context)
                    .data(R.drawable.img_veluna_stage)
                    .size(240)
                    .allowHardware(true)
                    .build()
            }
            AsyncImage(
                model = stageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.24f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(washBrush)
        )
    }
}

@Composable
fun VelunaArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    specialId: String? = null,
    isCircle: Boolean = false,
    targetSizePx: Int = 360,
    crossfade: Boolean = false
) {
    val context = LocalContext.current
    val effectiveShape = if (isCircle) CircleShape else shape
    val resolvedUrl = remember(url, targetSizePx) {
        InnerTubeParsers.upgradeThumbnailUrl(url, targetSizePx)
    }
    Box(
        modifier = modifier
            .clip(effectiveShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            specialId == LIKED_MUSIC_ID -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFEA580C),
                                    Color(0xFFBE123C)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = contentDescription,
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize(0.42f)
                    )
                }
            }
            specialId == ON_REPEAT_ID -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFD97706),
                                    Color(0xFFEA580C),
                                    Color(0xFF7C2D12)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Repeat,
                        contentDescription = contentDescription,
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize(0.45f)
                    )
                }
            }
            !resolvedUrl.isNullOrBlank() -> {
                val request = remember(context, resolvedUrl, targetSizePx, crossfade) {
                    ImageRequest.Builder(context)
                        .data(resolvedUrl)
                        .size(targetSizePx)
                        .crossfade(crossfade)
                        .allowHardware(true)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Icon(
                    imageVector = if (isCircle) Icons.Rounded.MusicNote else Icons.Rounded.LibraryMusic,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxSize(0.4f)
                )
            }
        }
    }
}
