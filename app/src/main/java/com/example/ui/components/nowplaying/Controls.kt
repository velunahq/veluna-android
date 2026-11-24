package com.example.ui.components.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.RepeatMode
import com.example.model.SongItem
import com.example.playback.PlaybackPositionState
import com.example.playback.PlaybackUiState
import com.example.ui.theme.VelunaMotion
import kotlinx.coroutines.flow.StateFlow

private val VelunaHeartRed = Color(0xFFEF4444)

@Composable
fun PlayerDeck(
    song: SongItem,
    playbackState: PlaybackUiState,
    positionFlow: StateFlow<PlaybackPositionState>,
    isLiked: Boolean,
    isDownloaded: Boolean,
    classicLayout: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onToggleDownload: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (classicLayout) 12.dp else 14.dp, Alignment.CenterVertically)
    ) {
        NowPlayingArtwork(
            thumbnailUrl = song.thumbnail,
            title = song.title,
            isPlaying = playbackState.isPlaying,
            compact = classicLayout,
            modifier = Modifier.weight(1f, fill = false)
        )

        if (classicLayout) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerDeckInnerContent(
                    song = song,
                    playbackState = playbackState,
                    positionFlow = positionFlow,
                    isLiked = isLiked,
                    isDownloaded = isDownloaded,
                    classicLayout = true,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekTo = onSeekTo,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onToggleLike = onToggleLike,
                    onSetSpeed = onSetSpeed,
                    onOpenSleepTimer = onOpenSleepTimer,
                    onOpenEqualizer = onOpenEqualizer,
                    onToggleDownload = onToggleDownload,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick
                )
            }
        } else {
            val deckShape = RoundedCornerShape(28.dp)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = deckShape,
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)
                        )
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlayerDeckInnerContent(
                        song = song,
                        playbackState = playbackState,
                        positionFlow = positionFlow,
                        isLiked = isLiked,
                        isDownloaded = isDownloaded,
                        classicLayout = false,
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekTo = onSeekTo,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onToggleLike = onToggleLike,
                        onSetSpeed = onSetSpeed,
                        onOpenSleepTimer = onOpenSleepTimer,
                        onOpenEqualizer = onOpenEqualizer,
                        onToggleDownload = onToggleDownload,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerDeckInnerContent(
    song: SongItem,
    playbackState: PlaybackUiState,
    positionFlow: StateFlow<PlaybackPositionState>,
    isLiked: Boolean,
    isDownloaded: Boolean,
    classicLayout: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onToggleDownload: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit
) {
    val likeScale by animateFloatAsState(
        targetValue = if (isLiked) 1.18f else 1f,
        animationSpec = VelunaMotion.bouncyScaleSpring(),
        label = "deck_like_scale"
    )
    val likeTint by animateColorAsState(
        targetValue = if (isLiked) VelunaHeartRed else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = VelunaMotion.snappySpring(),
        label = "deck_like_tint"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            val artistId = song.artistId ?: song.artistRuns.firstOrNull { it.id != null }?.id
            Text(
                text = song.artists.ifBlank { stringResource(R.string.player_unknown_artist) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = artistId != null) {
                    artistId?.let(onArtistClick)
                }
            )
            if (!song.album.isNullOrBlank()) {
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = !song.albumId.isNullOrBlank()) {
                        song.albumId?.let(onAlbumClick)
                    }
                )
            }
        }

        if (!song.isLocal) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = if (isLiked) {
                    VelunaHeartRed.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
                },
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (isLiked) VelunaHeartRed.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)
                )
            ) {
                IconButton(
                    onClick = onToggleLike,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_like_btn")
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(
                            if (isLiked) R.string.player_unlike else R.string.player_like
                        ),
                        tint = likeTint,
                        modifier = Modifier.graphicsLayer {
                            scaleX = likeScale
                            scaleY = likeScale
                        }
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = !playbackState.errorMessage.isNullOrBlank()) {
        Text(
            text = playbackState.errorMessage.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    TrackSeekBar(
        positionFlow = positionFlow,
        onSeekTo = onSeekTo
    )

    // Tactile Transport Row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier
                .size(48.dp)
                .testTag("now_playing_shuffle_btn")
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = stringResource(R.string.player_shuffle),
                tint = if (playbackState.shuffle) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        IconButton(
            onClick = onSkipPrevious,
            modifier = Modifier
                .size(52.dp)
                .testTag("now_playing_prev_btn")
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.player_previous),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(34.dp)
            )
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .size(if (classicLayout) 58.dp else 64.dp)
                .shadow(
                    elevation = if (classicLayout) 4.dp else 6.dp,
                    shape = CircleShape,
                    ambientColor = MaterialTheme.colorScheme.primary,
                    spotColor = MaterialTheme.colorScheme.primary
                )
                .testTag("now_playing_play_pause_btn")
        ) {
            if (playbackState.isBuffering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                AnimatedContent(
                    targetState = playbackState.isPlaying,
                    transitionSpec = {
                        (scaleIn(VelunaMotion.bouncyScaleSpring(), initialScale = 0.7f) + fadeIn(VelunaMotion.fastFade()))
                            .togetherWith(scaleOut(VelunaMotion.snappySpring(), targetScale = 0.7f) + fadeOut(VelunaMotion.fastFade()))
                    },
                    label = "deck_play_pause"
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (playing) R.string.action_pause else R.string.action_play
                        ),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onSkipNext,
            modifier = Modifier
                .size(52.dp)
                .testTag("now_playing_next_btn")
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.player_next),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(34.dp)
            )
        }

        IconButton(
            onClick = onCycleRepeat,
            modifier = Modifier
                .size(48.dp)
                .testTag("now_playing_repeat_btn")
        ) {
            Icon(
                imageVector = if (playbackState.repeatMode == RepeatMode.ONE) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                },
                contentDescription = stringResource(R.string.player_repeat),
                tint = if (playbackState.repeatMode != RepeatMode.OFF) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }

    DeckUtilityRow(
        song = song,
        playbackState = playbackState,
        isDownloaded = isDownloaded,
        classicLayout = classicLayout,
        onSetSpeed = onSetSpeed,
        onOpenSleepTimer = onOpenSleepTimer,
        onOpenEqualizer = onOpenEqualizer,
        onToggleDownload = onToggleDownload
    )
}
