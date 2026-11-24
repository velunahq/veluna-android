package com.example.ui.components.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.ui.components.VelunaArtwork
import com.example.ui.theme.VelunaMotion

@Composable
fun NowPlayingArtwork(
    thumbnailUrl: String?,
    title: String,
    isPlaying: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val artPlayScale by animateFloatAsState(
        targetValue = if (isPlaying || compact) 1.0f else 0.94f,
        animationSpec = VelunaMotion.expressiveSpatialSpring(),
        label = "hero_art_play_scale"
    )
    val heroShape = RoundedCornerShape(if (compact) 16.dp else 24.dp)

    Box(
        modifier = modifier
            .widthIn(max = if (compact) 230.dp else 330.dp)
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = artPlayScale
                scaleY = artPlayScale
            }
            .shadow(if (compact) 6.dp else 12.dp, heroShape)
            .clip(heroShape)
            .then(
                if (compact) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        shape = heroShape
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.28f),
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                Color.Transparent
                            )
                        ),
                        shape = heroShape
                    )
                }
            )
    ) {
        VelunaArtwork(
            url = thumbnailUrl,
            contentDescription = title,
            shape = heroShape,
            targetSizePx = 720,
            crossfade = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}
