package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.example.VelunaAppContainer
import com.example.data.local.VelunaUserPrefs
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.LOCAL_PLAYLIST_PREFIX
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.playback.PlaybackUiState
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.CreateEditPlaylistDialog
import com.example.ui.components.EditHomeDialog
import com.example.ui.components.EqualizerBottomSheet
import com.example.ui.components.ImportPlaylistDialog
import com.example.ui.components.NowPlayingOverlay
import com.example.ui.components.SleepTimerDialog
import com.example.ui.components.TrackMenuSheet
import com.example.ui.navigation.AlbumRoute
import com.example.ui.navigation.ArtistRoute
import com.example.ui.navigation.PlaylistRoute
import com.example.ui.screens.TasteOnboardingScreen
import com.example.ui.theme.VelunaMotion
import com.example.ui.viewmodel.HomeSearchViewModel
import com.example.ui.viewmodel.LibraryStatsViewModel
import com.example.ui.viewmodel.NowPlayingViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun VelunaGlobalOverlaysAndDialogs(
    container: VelunaAppContainer,
    navController: NavHostController,
    homeSearchVm: HomeSearchViewModel,
    nowPlayingVm: NowPlayingViewModel,
    libraryStatsVm: LibraryStatsViewModel,
    prefs: VelunaUserPrefs,
    playbackFlow: StateFlow<PlaybackUiState>,
    playlists: List<BrowseItem>,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    tasteProfile: TasteProfile?,
    ensureNotificationPermission: () -> Unit,
    menuTrack: SongItem?,
    onOpenMenuTrack: (SongItem) -> Unit,
    onDismissMenuTrack: () -> Unit,
    addToPlaylistTrack: SongItem?,
    onOpenAddToPlaylist: (SongItem) -> Unit,
    onDismissAddToPlaylist: () -> Unit,
    showCreatePlaylist: Boolean,
    onDismissCreatePlaylist: () -> Unit,
    showImportPlaylist: Boolean,
    onDismissImportPlaylist: () -> Unit,
    showSleepTimer: Boolean,
    onOpenSleepTimer: () -> Unit,
    onDismissSleepTimer: () -> Unit,
    showEqualizer: Boolean,
    onOpenEqualizer: () -> Unit,
    onDismissEqualizer: () -> Unit,
    showEditHome: Boolean,
    onDismissEditHome: () -> Unit,
    showTasteEditor: Boolean,
    onDismissTasteEditor: () -> Unit
) {
    val nowPlayingVisible by remember(playbackFlow) {
        playbackFlow.map { it.nowPlayingOpen && it.nowPlaying != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(nowPlayingVisible) {
        container.playerController.setPositionUiActive(nowPlayingVisible)
    }
    DisposableEffect(container.playerController) {
        onDispose { container.playerController.setPositionUiActive(false) }
    }

    AnimatedVisibility(
        visible = nowPlayingVisible,
        enter = slideInVertically(
            animationSpec = VelunaMotion.expressiveIntOffsetSpring(),
            initialOffsetY = { it }
        ) + fadeIn(VelunaMotion.fastFade()),
        exit = slideOutVertically(
            animationSpec = VelunaMotion.snappyIntOffsetSpring(),
            targetOffsetY = { it }
        ) + fadeOut(VelunaMotion.fastFade())
    ) {
        val playbackState by playbackFlow.collectAsStateWithLifecycle()
        val lyricsState by nowPlayingVm.lyricsState.collectAsStateWithLifecycle()
        val commentsState by nowPlayingVm.commentsState.collectAsStateWithLifecycle()
        val curSong = playbackState.nowPlaying
        val isCurDownloaded = curSong != null && curSong.videoId in downloadedIds

        NowPlayingOverlay(
            playbackState = playbackState,
            positionFlow = container.playerController.positionState,
            isLiked = curSong?.videoId in likedIds,
            prefs = prefs,
            lyricsState = lyricsState,
            commentsState = commentsState,
            isDownloaded = isCurDownloaded,
            onClose = { container.playerController.openNowPlaying(false) },
            onTogglePlayPause = { container.playerController.togglePlayPause() },
            onSeekTo = { container.playerController.seekTo(it) },
            onSkipNext = { container.playerController.skipNext() },
            onSkipPrevious = { container.playerController.skipPrevious() },
            onToggleShuffle = { container.playerController.toggleShuffle() },
            onCycleRepeat = { container.playerController.cycleRepeat() },
            onToggleLike = { container.playerController.toggleLikeCurrent() },
            onSetSpeed = { container.playerController.setPlaybackSpeed(it) },
            onOpenSleepTimer = onOpenSleepTimer,
            onOpenEqualizer = onOpenEqualizer,
            onToggleDownload = {
                curSong?.let { song ->
                    if (isCurDownloaded) container.playerController.deleteDownload(song.videoId)
                    else container.playerController.downloadTrack(song)
                }
            },
            onOpenMoreMenu = onOpenMenuTrack,
            onSelectQueueIndex = { container.playerController.playIndex(it) },
            onRemoveQueueIndex = { container.playerController.removeFromQueue(it) },
            onClearQueue = { container.playerController.clearQueue() },
            onToggleRomanize = nowPlayingVm::toggleRomanize,
            onSelectLyricsProvider = { pid ->
                curSong?.let { nowPlayingVm.loadLyrics(it, forceProviderId = pid) }
            },
            onResetPinnedLyrics = nowPlayingVm::resetPinnedLyrics,
            onNudgeLyricsOffset = nowPlayingVm::nudgeLyricsOffset,
            onLoadComments = nowPlayingVm::loadCommentsIfNeeded,
            onArtistClick = { artistId ->
                container.playerController.openNowPlaying(false)
                navController.navigate(ArtistRoute(artistId))
            },
            onAlbumClick = { albumId ->
                container.playerController.openNowPlaying(false)
                navController.navigate(AlbumRoute(albumId))
            }
        )
    }

    val showOnboardingOverlay = (tasteProfile != null && tasteProfile.onboardingCompleted == false) || showTasteEditor
    AnimatedVisibility(
        visible = showOnboardingOverlay,
        enter = slideInVertically(
            animationSpec = VelunaMotion.expressiveIntOffsetSpring(),
            initialOffsetY = { it / 2 }
        ) + fadeIn(VelunaMotion.fastFade()),
        exit = slideOutVertically(
            animationSpec = VelunaMotion.snappyIntOffsetSpring(),
            targetOffsetY = { it / 2 }
        ) + fadeOut(VelunaMotion.fastFade())
    ) {
        val onboardingSearch by homeSearchVm.onboardingSearch.collectAsStateWithLifecycle()
        TasteOnboardingScreen(
            initialProfile = tasteProfile,
            searchState = onboardingSearch,
            canNavigateBack = tasteProfile?.onboardingCompleted == true,
            onQueryChange = homeSearchVm::onOnboardingQueryChange,
            onSave = { genres, artists, songs ->
                homeSearchVm.saveOnboardingTaste(genres, artists, songs) {
                    onDismissTasteEditor()
                }
            },
            onSkip = {
                if (tasteProfile?.onboardingCompleted == true) {
                    onDismissTasteEditor()
                } else {
                    homeSearchVm.skipOnboarding {
                        onDismissTasteEditor()
                    }
                }
            }
        )
    }

    menuTrack?.let { song ->
        val isDl = song.videoId in downloadedIds
        TrackMenuSheet(
            song = song,
            isDownloaded = isDl,
            isLiked = song.videoId in likedIds,
            onDismiss = onDismissMenuTrack,
            onPlayNext = { container.playerController.playNext(song) },
            onAddToQueue = { container.playerController.addToQueue(song) },
            onStartRadio = {
                ensureNotificationPermission()
                container.playerController.startRadio(song)
            },
            onToggleLike = { container.playerController.toggleLike(song) },
            onAddToPlaylist = { onOpenAddToPlaylist(song) },
            onToggleDownload = {
                if (isDl) container.playerController.deleteDownload(song.videoId)
                else container.playerController.downloadTrack(song)
            },
            onGoToArtist = { artistId ->
                container.playerController.openNowPlaying(false)
                navController.navigate(ArtistRoute(artistId))
            },
            onGoToAlbum = { albumId ->
                container.playerController.openNowPlaying(false)
                navController.navigate(AlbumRoute(albumId))
            },
            onPinShortcut = {
                homeSearchVm.addShortcut(
                    BrowseItem(BrowseKind.SONG, song.videoId, song.title, song.artists, song.thumbnail)
                )
                libraryStatsVm.postStatus("Pinned to Shortcuts")
            },
            onBlockArtist = {
                libraryStatsVm.blockArtist(song.artists.substringBefore(',').trim(), song.artistId)
            }
        )
    }

    addToPlaylistTrack?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = playlists,
            onDismiss = onDismissAddToPlaylist,
            onPickPlaylist = { plId -> libraryStatsVm.addTrackToPlaylist(plId, song) },
            onCreateNew = { title -> libraryStatsVm.createPlaylist(title, initialSong = song) }
        )
    }

    if (showCreatePlaylist) {
        CreateEditPlaylistDialog(
            onDismiss = onDismissCreatePlaylist,
            onConfirm = { title, desc, _ -> libraryStatsVm.createPlaylist(title, desc) }
        )
    }

    if (showImportPlaylist) {
        val importState by libraryStatsVm.importState.collectAsStateWithLifecycle()
        ImportPlaylistDialog(
            state = importState,
            onDismiss = onDismissImportPlaylist,
            onImportYouTube = libraryStatsVm::importYouTubePlaylist,
            onImportFile = libraryStatsVm::importCsvOrM3u,
            onOpenPlaylist = { id ->
                navController.navigate(PlaylistRoute("$LOCAL_PLAYLIST_PREFIX$id"))
            }
        )
    }

    if (showSleepTimer) {
        val sleepTimerRemainingMs by remember(playbackFlow) {
            playbackFlow.map { it.sleepTimerRemainingMs }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = null)
        SleepTimerDialog(
            activeRemainingMs = sleepTimerRemainingMs,
            onDismiss = onDismissSleepTimer,
            onSelectMinutes = { container.playerController.setSleepTimer(it) }
        )
    }

    if (showEqualizer) {
        EqualizerBottomSheet(
            enabled = prefs.eqEnabled,
            presetName = prefs.eqPresetName,
            bandsDb = prefs.eqBandsDb,
            loudnessNormEnabled = prefs.loudnessNormalization,
            onToggleEnabled = nowPlayingVm::setEqEnabled,
            onSelectPreset = nowPlayingVm::applyEqPreset,
            onBandChange = nowPlayingVm::setEqBand,
            onToggleLoudness = libraryStatsVm::setLoudnessNormalization,
            onDismiss = onDismissEqualizer
        )
    }

    if (showEditHome) {
        val shortcuts by homeSearchVm.shortcuts.collectAsStateWithLifecycle()
        EditHomeDialog(
            showMoodChips = prefs.showMoodChips,
            showHeroBackdrop = prefs.showHeroArtworkBackdrop,
            shortcuts = shortcuts,
            onToggleMoodChips = libraryStatsVm::setShowMoodChips,
            onToggleHeroBackdrop = libraryStatsVm::setShowHeroBackdrop,
            onRemoveShortcut = homeSearchVm::removeShortcut,
            onDismiss = onDismissEditHome
        )
    }
}
