package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.playback.PlaybackPositionState
import com.example.playback.PlaybackUiState
import com.example.ui.theme.VelunaMotion
import kotlinx.coroutines.flow.StateFlow

private val VelunaHeartRed = Color(0xFFEF4444)

@Composable
fun MiniPlayerBar(
    playbackFlow: StateFlow<PlaybackUiState>,
    positionFlow: StateFlow<PlaybackPositionState>,
    likedIds: Set<String>,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by playbackFlow.collectAsStateWithLifecycle()
    val song = state.nowPlaying ?: return
    val isLiked = song.videoId in likedIds
    val likeScale by animateFloatAsState(
        targetValue = if (isLiked) 1.15f else 1f,
        animationSpec = VelunaMotion.bouncyScaleSpring(),
        label = "mini_like_scale"
    )
    val likeTint by animateColorAsState(
        targetValue = if (isLiked) VelunaHeartRed else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = VelunaMotion.snappySpring(),
        label = "mini_like_tint"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onOpenNowPlaying)
            .testTag("mini_player_bar")
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VelunaArtwork(
                    url = song.thumbnail,
                    contentDescription = song.title,
                    shape = MaterialTheme.shapes.small,
                    targetSizePx = 180,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = song.artists.ifBlank { stringResource(R.string.player_unknown_artist) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!song.isLocal) {
                    IconButton(
                        onClick = onToggleLike,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("mini_player_like")
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = stringResource(if (isLiked) R.string.player_unlike else R.string.player_like),
                            tint = likeTint,
                            modifier = Modifier.graphicsLayer {
                                scaleX = likeScale
                                scaleY = likeScale
                            }
                        )
                    }
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_player_play_pause")
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        AnimatedContent(
                            targetState = state.isPlaying,
                            transitionSpec = {
                                (scaleIn(VelunaMotion.bouncyScaleSpring(), initialScale = 0.72f) + fadeIn(VelunaMotion.fastFade()))
                                    .togetherWith(scaleOut(VelunaMotion.snappySpring(), targetScale = 0.72f) + fadeOut(VelunaMotion.fastFade()))
                            },
                            label = "mini_play_icon"
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_player_next")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.player_next_track),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            MiniProgressBar(positionFlow = positionFlow)
        }
    }
}

@Composable
private fun MiniProgressBar(positionFlow: StateFlow<PlaybackPositionState>) {
    val posState by positionFlow.collectAsStateWithLifecycle()
    val rawProgress = if (posState.durationMs > 0L) {
        (posState.positionMs.toFloat() / posState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    LinearProgressIndicator(
        progress = { rawProgress },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
    )
}
