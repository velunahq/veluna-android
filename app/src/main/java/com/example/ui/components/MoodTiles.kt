package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.MoodTile
import com.example.ui.theme.parseHexColorOrNull

@Composable
fun MoodGridTile(
    tile: MoodTile,
    sectionTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceBase = MaterialTheme.colorScheme.surfaceContainerHigh
    val baseColor = remember(tile.colorHex, primary) { parseHexColorOrNull(tile.colorHex) ?: primary }
    val gradientBrush = remember(surfaceBase, baseColor) {
        val tintWash = lerp(surfaceBase, baseColor, 0.22f)
        Brush.horizontalGradient(listOf(tintWash, surfaceBase))
    }
    val isGenre = remember(sectionTitle) { sectionTitle.contains("genre", ignoreCase = true) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = surfaceBase,
        modifier = modifier
            .height(74.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("mood_tile_${tile.title.lowercase().replace(' ', '_')}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(baseColor)
            )
            if (isGenre) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 10.dp, y = 10.dp)
                        .rotate(18f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(baseColor.copy(alpha = 0.42f))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 14.dp, y = 14.dp)
                        .clip(CircleShape)
                        .background(baseColor.copy(alpha = 0.38f))
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(surfaceBase.copy(alpha = 0.72f))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 28.dp)
                )
            }
        }
    }
}
