package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.VelunaUserPrefs
import com.example.model.Rating
import com.example.model.SongItem
import com.example.playback.PlaybackPositionState
import com.example.playback.PlaybackUiState
import com.example.ui.components.nowplaying.NowPlayingCompactTabsBar
import com.example.ui.components.nowplaying.NowPlayingTopBar
import com.example.ui.components.nowplaying.NowPlayingWideSidePanel
import com.example.ui.components.nowplaying.PlayerDeck
import com.example.ui.theme.VelunaMotion
import com.example.ui.viewmodel.CommentsUiState
import com.example.ui.viewmodel.LyricsUiState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun NowPlayingOverlay(
    playbackState: PlaybackUiState,
    positionFlow: StateFlow<PlaybackPositionState>,
    isLiked: Boolean = playbackState.nowPlaying?.rating == Rating.LIKE,
    prefs: VelunaUserPrefs,
    lyricsState: LyricsUiState,
    commentsState: CommentsUiState,
    isDownloaded: Boolean,
    onClose: () -> Unit,
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
    onOpenMoreMenu: (SongItem) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleRomanize: () -> Unit,
    onSelectLyricsProvider: (String) -> Unit,
    onResetPinnedLyrics: () -> Unit,
    onNudgeLyricsOffset: (Long) -> Unit,
    onLoadComments: (SongItem) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit
) {
    val song = playbackState.nowPlaying ?: return
    BackHandler { onClose() }

    var compactTab by remember { mutableIntStateOf(0) } // 0=Player, 1=Queue, 2=Lyrics, 3=Comments
    var widePanelTab by remember { mutableIntStateOf(0) } // 0=Queue, 1=Lyrics, 2=Comments
    val queueListState = rememberLazyListState()
    val lyricsListState = rememberLazyListState()
    val commentsListState = rememberLazyListState()

    LaunchedEffect(compactTab, widePanelTab, song.videoId) {
        if (compactTab == 3 || widePanelTab == 2) {
            onLoadComments(song)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("now_playing_screen")
    ) {
        ArtWashBackdrop(
            artworkUrl = song.thumbnail,
            accentColor = playbackState.artworkAccentColor,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            NowPlayingTopBar(
                sourceName = playbackState.sourceName,
                streamClient = playbackState.streamClient,
                onClose = onClose,
                onOpenMore = { onOpenMoreMenu(song) }
            )

            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val isWide = maxWidth >= 720.dp

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        PlayerDeck(
                            song = song,
                            playbackState = playbackState,
                            positionFlow = positionFlow,
                            isLiked = isLiked,
                            isDownloaded = isDownloaded,
                            classicLayout = prefs.classicPlayerLayout,
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
                            onAlbumClick = onAlbumClick,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        NowPlayingWideSidePanel(
                            selectedTab = widePanelTab,
                            onSelectTab = { widePanelTab = it },
                            playbackState = playbackState,
                            positionFlow = positionFlow,
                            lyricsState = lyricsState,
                            commentsState = commentsState,
                            romanizeEnabled = prefs.romanizeLyrics,
                            queueListState = queueListState,
                            lyricsListState = lyricsListState,
                            commentsListState = commentsListState,
                            onSelectQueueIndex = onSelectQueueIndex,
                            onRemoveQueueIndex = onRemoveQueueIndex,
                            onClearQueue = onClearQueue,
                            onToggleRomanize = onToggleRomanize,
                            onSelectLyricsProvider = onSelectLyricsProvider,
                            onResetPinnedLyrics = onResetPinnedLyrics,
                            onNudgeLyricsOffset = onNudgeLyricsOffset,
                            onSeekTo = onSeekTo,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = compactTab,
                                transitionSpec = {
                                    val dir = if (targetState >= initialState) 1 else -1
                                    (slideInHorizontally(VelunaMotion.screenSlideEnterSpring()) { dir * (it / 16) } +
                                        fadeIn(VelunaMotion.tabTransitionTween()))
                                        .togetherWith(
                                            slideOutHorizontally(tween(150)) { -dir * (it / 24) } +
                                                fadeOut(VelunaMotion.tabFadeOutTween())
                                        )
                                },
                                label = "compact_now_playing_tab"
                            ) { tab ->
                                when (tab) {
                                    0 -> PlayerDeck(
                                        song = song,
                                        playbackState = playbackState,
                                        positionFlow = positionFlow,
                                        isLiked = isLiked,
                                        isDownloaded = isDownloaded,
                                        classicLayout = prefs.classicPlayerLayout,
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
                                        onAlbumClick = onAlbumClick,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 18.dp, vertical = 6.dp)
                                    )
                                    1 -> QueuePanel(
                                        queue = playbackState.queue,
                                        currentIndex = playbackState.currentIndex,
                                        sourceName = playbackState.sourceName,
                                        listState = queueListState,
                                        onSelectIndex = onSelectQueueIndex,
                                        onRemoveIndex = onRemoveQueueIndex,
                                        onClearQueue = onClearQueue
                                    )
                                    2 -> SyncedLyricsPanel(
                                        lyricsState = lyricsState,
                                        positionFlow = positionFlow,
                                        romanizeEnabled = prefs.romanizeLyrics,
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
                        NowPlayingCompactTabsBar(
                            selectedTab = compactTab,
                            onSelectTab = { compactTab = it }
                        )
                    }
                }
            }
        }
    }
}
