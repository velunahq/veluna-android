package com.example.ui.components.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.playback.PlaybackPositionState
import com.example.playback.PlaybackUiState
import com.example.ui.components.CommentsPanel
import com.example.ui.components.QueuePanel
import com.example.ui.components.SyncedLyricsPanel
import com.example.ui.theme.VelunaMotion
import com.example.ui.viewmodel.CommentsUiState
import com.example.ui.viewmodel.LyricsUiState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun NowPlayingTopBar(
    sourceName: String?,
    streamClient: String?,
    onClose: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassPillBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    val glassBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            color = glassPillBg,
            shape = CircleShape,
            border = BorderStroke(1.dp, glassBorderColor)
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("now_playing_collapse_btn")
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.player_minimize),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Surface(
            color = glassPillBg,
            shape = CircleShape,
            border = BorderStroke(1.dp, glassBorderColor),
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 10.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = sourceName ?: stringResource(R.string.player_now_playing_default),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!streamClient.isNullOrBlank()) {
                    Text(
                        text = streamClient,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Surface(
            color = glassPillBg,
            shape = CircleShape,
            border = BorderStroke(1.dp, glassBorderColor)
        ) {
            IconButton(
                onClick = onOpenMore,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("now_playing_more_btn")
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.player_more_options),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun NowPlayingWideSidePanel(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    playbackState: PlaybackUiState,
    positionFlow: StateFlow<PlaybackPositionState>,
    lyricsState: LyricsUiState,
    commentsState: CommentsUiState,
    romanizeEnabled: Boolean,
    onSelectQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleRomanize: () -> Unit,
    onSelectLyricsProvider: (String) -> Unit,
    onResetPinnedLyrics: () -> Unit,
    onNudgeLyricsOffset: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    queueListState: LazyListState = rememberLazyListState(),
    lyricsListState: LazyListState = rememberLazyListState(),
    commentsListState: LazyListState = rememberLazyListState()
) {
    val glassBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f)
    val glassBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    Surface(
        color = glassBg,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, glassBorderColor),
        modifier = modifier
    ) {
        Column {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onSelectTab(0) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(R.string.player_queue), maxLines = 1, softWrap = false) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onSelectTab(1) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(R.string.player_lyrics), maxLines = 1, softWrap = false) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { onSelectTab(2) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(R.string.player_comments), maxLines = 1, softWrap = false) }
                )
            }
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState >= initialState) 1 else -1
                    (slideInHorizontally(VelunaMotion.screenSlideEnterSpring()) { dir * (it / 16) } +
                        fadeIn(VelunaMotion.tabTransitionTween()))
                        .togetherWith(
                            slideOutHorizontally(tween(150)) { -dir * (it / 24) } +
                                fadeOut(VelunaMotion.tabFadeOutTween())
                        )
                },
                label = "wide_now_playing_tab"
            ) { tab ->
                when (tab) {
                    0 -> QueuePanel(
                        queue = playbackState.queue,
                        currentIndex = playbackState.currentIndex,
                        sourceName = playbackState.sourceName,
                        listState = queueListState,
                        onSelectIndex = onSelectQueueIndex,
                        onRemoveIndex = onRemoveQueueIndex,
                        onClearQueue = onClearQueue
                    )
                    1 -> SyncedLyricsPanel(
                        lyricsState = lyricsState,
                        positionFlow = positionFlow,
                        romanizeEnabled = romanizeEnabled,
                        listState = lyricsListState,
                        onToggleRomanize = onToggleRomanize,
                        onSelectProvider = onSelectLyricsProvider,
                        onResetPinned = onResetPinnedLyrics,
                        onNudgeOffset = onNudgeLyricsOffset,
                        onSeekToLine = onSeekTo
                    )
                    else -> CommentsPanel(
                        state = commentsState,
                        listState = commentsListState
                    )
                }
            }
        }
    }
}

@Composable
fun NowPlayingCompactTabsBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val glassBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column {
            HorizontalDivider(color = glassBorderColor)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onSelectTab(0) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            stringResource(R.string.player_tab_player),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onSelectTab(1) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            stringResource(R.string.player_queue),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { onSelectTab(2) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            stringResource(R.string.player_lyrics),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = { Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { onSelectTab(3) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            stringResource(R.string.player_comments),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = { Icon(Icons.AutoMirrored.Rounded.Comment, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        }
    }
}
