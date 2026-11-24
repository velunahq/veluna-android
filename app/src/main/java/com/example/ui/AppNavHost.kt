package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.example.VelunaAppContainer
import com.example.model.BrowseItem
import com.example.model.DownloadProgress
import com.example.model.MoodTile
import com.example.model.SongItem
import com.example.playback.PlaybackUiState
import com.example.ui.navigation.AlbumRoute
import com.example.ui.navigation.ArtistRoute
import com.example.ui.navigation.HistoryRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.PlaylistRoute
import com.example.ui.navigation.VelunaTopTab
import com.example.ui.screens.AlbumDetailScreen
import com.example.ui.screens.ArtistDetailScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StatsScreen
import com.example.ui.theme.VelunaMotion
import com.example.ui.viewmodel.DetailViewModel
import com.example.ui.viewmodel.HomeSearchViewModel
import com.example.ui.viewmodel.LibraryStatsViewModel
import kotlinx.coroutines.flow.StateFlow

@Stable
class VelunaTabNavState(
    selectedTabState: MutableState<VelunaTopTab>,
    previousTabState: MutableState<VelunaTopTab>,
    visitedMaskState: MutableIntState
) {
    var selectedTab: VelunaTopTab by selectedTabState
        private set

    var previousTab: VelunaTopTab by previousTabState
        private set

    var visitedMask: Int by visitedMaskState
        private set

    fun selectTab(tab: VelunaTopTab) {
        val flag = 1 shl tab.ordinal
        if ((visitedMask and flag) == 0) {
            visitedMask = visitedMask or flag
        }
        if (selectedTab != tab) {
            previousTab = selectedTab
            selectedTab = tab
        }
    }

    fun isSelected(tab: VelunaTopTab): Boolean = selectedTab == tab

    fun isVisited(tab: VelunaTopTab): Boolean = (visitedMask and (1 shl tab.ordinal)) != 0
}

@Composable
fun rememberVelunaTabNavState(
    initialTab: VelunaTopTab = VelunaTopTab.HOME
): VelunaTabNavState {
    val selectedTabState = rememberSaveable { mutableStateOf(initialTab) }
    val previousTabState = rememberSaveable { mutableStateOf(initialTab) }
    val visitedMaskState = rememberSaveable { mutableIntStateOf(1 shl initialTab.ordinal) }
    return remember(selectedTabState, previousTabState, visitedMaskState) {
        VelunaTabNavState(selectedTabState, previousTabState, visitedMaskState)
    }
}

@Composable
fun VelunaNavHost(
    navController: NavHostController,
    tabNavState: VelunaTabNavState,
    isAtTopTabRootState: State<Boolean>,
    container: VelunaAppContainer,
    homeSearchVm: HomeSearchViewModel,
    detailVm: DetailViewModel,
    libraryStatsVm: LibraryStatsViewModel,
    playbackState: State<PlaybackUiState>,
    downloadsFlow: StateFlow<Map<String, DownloadProgress>>,
    ensureNotificationPermission: () -> Unit,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenEditHome: () -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onOpenImportPlaylist: () -> Unit,
    onOpenTasteOnboarding: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enterAnim = remember {
        slideInHorizontally(
            animationSpec = VelunaMotion.screenSlideEnterSpring(),
            initialOffsetX = { (it * 0.16f).toInt() }
        ) + fadeIn(VelunaMotion.screenFadeInTween())
    }
    val exitAnim = remember {
        slideOutHorizontally(
            animationSpec = VelunaMotion.screenSlideExitTween(),
            targetOffsetX = { -(it * 0.06f).toInt() }
        ) + fadeOut(VelunaMotion.screenFadeOutTween())
    }
    val popEnterAnim = remember {
        slideInHorizontally(
            animationSpec = VelunaMotion.screenSlideEnterSpring(),
            initialOffsetX = { -(it * 0.14f).toInt() }
        ) + fadeIn(VelunaMotion.screenFadeInTween())
    }
    val popExitAnim = remember {
        slideOutHorizontally(
            animationSpec = VelunaMotion.screenSlideExitTween(),
            targetOffsetX = { (it * 0.16f).toInt() }
        ) + fadeOut(VelunaMotion.screenFadeOutTween())
    }

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        enterTransition = { enterAnim },
        exitTransition = { exitAnim },
        popEnterTransition = { popEnterAnim },
        popExitTransition = { popExitAnim },
        modifier = modifier
    ) {
        composable<HomeRoute> {
            TopLevelTabDeck(
                tabNavState = tabNavState,
                isAtTopTabRootState = isAtTopTabRootState,
                navController = navController,
                container = container,
                homeSearchVm = homeSearchVm,
                libraryStatsVm = libraryStatsVm,
                playbackState = playbackState,
                downloadsFlow = downloadsFlow,
                ensureNotificationPermission = ensureNotificationPermission,
                onNavigateTopTab = onNavigateTopTab,
                onPlayTracks = onPlayTracks,
                onOpenBrowseItem = onOpenBrowseItem,
                onTrackMore = onTrackMore,
                onOpenEditHome = onOpenEditHome,
                onOpenCreatePlaylist = onOpenCreatePlaylist,
                onOpenImportPlaylist = onOpenImportPlaylist,
                onOpenTasteOnboarding = onOpenTasteOnboarding,
                onOpenEqualizer = onOpenEqualizer
            )
        }

        composable<AlbumRoute> { entry ->
            val route = entry.toRoute<AlbumRoute>()
            val savedAlbumId by rememberSaveable(route.albumId) { mutableStateOf(route.albumId) }
            LaunchedEffect(savedAlbumId) { detailVm.loadAlbum(savedAlbumId) }
            val albumState by detailVm.albumState.collectAsStateWithLifecycle()
            val isSavedFlow = remember(detailVm, savedAlbumId) { detailVm.observeIsAlbumSaved(savedAlbumId) }
            val isSaved by isSavedFlow.collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackState) {
                derivedStateOf { playbackState.value.nowPlaying?.videoId }
            }
            val onBack = remember(navController) { { navController.popBackStack(); Unit } }
            val onToggleSave = remember(detailVm, isSaved) { { detailVm.toggleSaveAlbum(isSaved) } }
            val onOpenArtist = remember(navController) { { id: String -> navController.navigate(ArtistRoute(id)) } }

            AlbumDetailScreen(
                state = albumState,
                isSaved = isSaved,
                nowPlayingId = nowPlayingId,
                onBack = onBack,
                onToggleSave = onToggleSave,
                onPinShortcut = detailVm::pinAsShortcut,
                onPlayTracks = onPlayTracks,
                onTrackMore = onTrackMore,
                onOpenBrowseItem = onOpenBrowseItem,
                onOpenArtist = onOpenArtist
            )
        }

        composable<ArtistRoute> { entry ->
            val route = entry.toRoute<ArtistRoute>()
            val savedChannelId by rememberSaveable(route.channelId) { mutableStateOf(route.channelId) }
            LaunchedEffect(savedChannelId) { detailVm.loadArtist(savedChannelId) }
            val artistState by detailVm.artistState.collectAsStateWithLifecycle()
            val isFollowedFlow = remember(detailVm, savedChannelId) { detailVm.observeIsArtistFollowed(savedChannelId) }
            val isFollowed by isFollowedFlow.collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackState) {
                derivedStateOf { playbackState.value.nowPlaying?.videoId }
            }
            val onBack = remember(navController) { { navController.popBackStack(); Unit } }
            val onToggleFollow = remember(detailVm, isFollowed) { { detailVm.toggleFollowArtist(isFollowed) } }

            ArtistDetailScreen(
                state = artistState,
                isFollowed = isFollowed,
                nowPlayingId = nowPlayingId,
                onBack = onBack,
                onToggleFollow = onToggleFollow,
                onPinShortcut = detailVm::pinAsShortcut,
                onPlayTracks = onPlayTracks,
                onTrackMore = onTrackMore,
                onOpenBrowseItem = onOpenBrowseItem
            )
        }

        composable<PlaylistRoute> { entry ->
            val route = entry.toRoute<PlaylistRoute>()
            val savedPlaylistId by rememberSaveable(route.playlistId) { mutableStateOf(route.playlistId) }
            LaunchedEffect(savedPlaylistId) { detailVm.loadPlaylist(savedPlaylistId, force = true) }
            val playlistState by detailVm.playlistState.collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackState) {
                derivedStateOf { playbackState.value.nowPlaying?.videoId }
            }
            val onBack = remember(navController) { { navController.popBackStack(); Unit } }
            val onRemoveDuplicates = remember(detailVm, libraryStatsVm) {
                { id: Long ->
                    detailVm.removeDuplicatesFromLocalPlaylist(id) { removed ->
                        libraryStatsVm.postStatus("Removed $removed duplicate track(s)")
                    }
                }
            }
            val onDeletePlaylist = remember(detailVm, navController) {
                { id: Long ->
                    detailVm.deleteLocalPlaylist(id) { navController.popBackStack() }
                }
            }

            PlaylistDetailScreen(
                state = playlistState,
                nowPlayingId = nowPlayingId,
                onBack = onBack,
                onFilterChange = detailVm::setPlaylistFilter,
                onSortChange = detailVm::setPlaylistSort,
                onPlayTracks = onPlayTracks,
                onTrackMore = onTrackMore,
                onEditLocalPlaylist = detailVm::updateLocalPlaylistDetails,
                onRemoveDuplicates = onRemoveDuplicates,
                onRemoveLocalTrack = detailVm::removeTrackFromLocalPlaylist,
                onDeletePlaylist = onDeletePlaylist,
                onExportM3u = detailVm::exportM3uText,
                onExportCsv = detailVm::exportCsvText,
                onPinShortcut = detailVm::pinAsShortcut,
                onStatusMessage = libraryStatsVm::postStatus
            )
        }

        composable<HistoryRoute> {
            val groups by libraryStatsVm.historyGroups.collectAsStateWithLifecycle()
            val onBack = remember(navController) { { navController.popBackStack(); Unit } }
            val onPlaySong = remember(ensureNotificationPermission, container) {
                { song: SongItem ->
                    ensureNotificationPermission()
                    container.playerController.playSong(song, "Listening History")
                }
            }
            HistoryScreen(
                groups = groups,
                onBack = onBack,
                onPlaySong = onPlaySong,
                onTrackMore = onTrackMore
            )
        }
    }
}

@Composable
private fun TopLevelTabDeck(
    tabNavState: VelunaTabNavState,
    isAtTopTabRootState: State<Boolean>,
    navController: NavHostController,
    container: VelunaAppContainer,
    homeSearchVm: HomeSearchViewModel,
    libraryStatsVm: LibraryStatsViewModel,
    playbackState: State<PlaybackUiState>,
    downloadsFlow: StateFlow<Map<String, DownloadProgress>>,
    ensureNotificationPermission: () -> Unit,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenEditHome: () -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onOpenImportPlaylist: () -> Unit,
    onOpenTasteOnboarding: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val backHandlerEnabled by remember(isAtTopTabRootState, tabNavState) {
        derivedStateOf {
            isAtTopTabRootState.value && !tabNavState.isSelected(VelunaTopTab.HOME)
        }
    }
    BackHandler(enabled = backHandlerEnabled) {
        onNavigateTopTab(VelunaTopTab.HOME)
    }

    val isHomeVisited by remember(tabNavState) { derivedStateOf { tabNavState.isVisited(VelunaTopTab.HOME) } }
    val isHomeSelected = remember(tabNavState) { derivedStateOf { tabNavState.isSelected(VelunaTopTab.HOME) } }

    val isSearchVisited by remember(tabNavState) { derivedStateOf { tabNavState.isVisited(VelunaTopTab.SEARCH) } }
    val isSearchSelected = remember(tabNavState) { derivedStateOf { tabNavState.isSelected(VelunaTopTab.SEARCH) } }

    val isLibraryVisited by remember(tabNavState) { derivedStateOf { tabNavState.isVisited(VelunaTopTab.LIBRARY) } }
    val isLibrarySelected = remember(tabNavState) { derivedStateOf { tabNavState.isSelected(VelunaTopTab.LIBRARY) } }

    val isStatsVisited by remember(tabNavState) { derivedStateOf { tabNavState.isVisited(VelunaTopTab.STATS) } }
    val isStatsSelected = remember(tabNavState) { derivedStateOf { tabNavState.isSelected(VelunaTopTab.STATS) } }

    val isSettingsVisited by remember(tabNavState) { derivedStateOf { tabNavState.isVisited(VelunaTopTab.SETTINGS) } }
    val isSettingsSelected = remember(tabNavState) { derivedStateOf { tabNavState.isSelected(VelunaTopTab.SETTINGS) } }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isHomeVisited) {
            KeepAliveTabSlot(tab = VelunaTopTab.HOME, activeState = isHomeSelected, tabNavState = tabNavState) {
                HomeTabPane(
                    navController = navController,
                    container = container,
                    homeSearchVm = homeSearchVm,
                    libraryStatsVm = libraryStatsVm,
                    playbackState = playbackState,
                    onNavigateTopTab = onNavigateTopTab,
                    onOpenBrowseItem = onOpenBrowseItem,
                    onTrackMore = onTrackMore,
                    onOpenEditHome = onOpenEditHome
                )
            }
        }

        if (isSearchVisited) {
            KeepAliveTabSlot(tab = VelunaTopTab.SEARCH, activeState = isSearchSelected, tabNavState = tabNavState) {
                SearchTabPane(
                    activeState = isSearchSelected,
                    homeSearchVm = homeSearchVm,
                    playbackState = playbackState,
                    onNavigateTopTab = onNavigateTopTab,
                    onOpenBrowseItem = onOpenBrowseItem,
                    onTrackMore = onTrackMore
                )
            }
        }

        if (isLibraryVisited) {
            KeepAliveTabSlot(tab = VelunaTopTab.LIBRARY, activeState = isLibrarySelected, tabNavState = tabNavState) {
                LibraryTabPane(
                    container = container,
                    libraryStatsVm = libraryStatsVm,
                    playbackState = playbackState,
                    downloadsFlow = downloadsFlow,
                    onOpenBrowseItem = onOpenBrowseItem,
                    onPlayTracks = onPlayTracks,
                    onTrackMore = onTrackMore,
                    onOpenCreatePlaylist = onOpenCreatePlaylist,
                    onOpenImportPlaylist = onOpenImportPlaylist
                )
            }
        }

        if (isStatsVisited) {
            KeepAliveTabSlot(tab = VelunaTopTab.STATS, activeState = isStatsSelected, tabNavState = tabNavState) {
                StatsTabPane(
                    navController = navController,
                    container = container,
                    libraryStatsVm = libraryStatsVm,
                    playbackState = playbackState,
                    ensureNotificationPermission = ensureNotificationPermission,
                    onTrackMore = onTrackMore
                )
            }
        }

        if (isSettingsVisited) {
            KeepAliveTabSlot(tab = VelunaTopTab.SETTINGS, activeState = isSettingsSelected, tabNavState = tabNavState) {
                SettingsTabPane(
                    container = container,
                    homeSearchVm = homeSearchVm,
                    libraryStatsVm = libraryStatsVm,
                    onOpenTasteOnboarding = onOpenTasteOnboarding,
                    onOpenEqualizer = onOpenEqualizer
                )
            }
        }
    }
}

@Composable
private fun KeepAliveTabSlot(
    tab: VelunaTopTab,
    activeState: State<Boolean>,
    tabNavState: VelunaTabNavState,
    content: @Composable () -> Unit
) {
    val active = activeState.value
    val density = LocalDensity.current
    val slideDistancePx = remember(density) { with(density) { 22.dp.toPx() } }
    val alpha = remember { Animatable(if (active) 1f else 0f) }
    val offsetX = remember { Animatable(0f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (!initialized) {
            initialized = true
            alpha.snapTo(if (active) 1f else 0f)
            offsetX.snapTo(0f)
            return@LaunchedEffect
        }
        if (active) {
            val dir = if (tab.ordinal >= tabNavState.previousTab.ordinal) 1f else -1f
            if (alpha.value < 0.05f) {
                offsetX.snapTo(slideDistancePx * dir)
            }
            coroutineScope {
                launch { alpha.animateTo(1f, VelunaMotion.tabTransitionTween()) }
                launch { offsetX.animateTo(0f, VelunaMotion.tabSlideSpring()) }
            }
        } else {
            val exitDir = if (tabNavState.selectedTab.ordinal >= tab.ordinal) -1f else 1f
            coroutineScope {
                launch { alpha.animateTo(0f, VelunaMotion.tabFadeOutTween()) }
                launch { offsetX.animateTo(slideDistancePx * 0.55f * exitDir, VelunaMotion.tabTransitionTween()) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (active) 1f else 0f)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (activeState.value || alpha.value > 0.005f) {
                        placeable.place(0, 0)
                    }
                }
            }
            .graphicsLayer {
                this.alpha = alpha.value
                this.translationX = offsetX.value
            }
            .focusProperties { canFocus = active }
            .semantics {
                if (!active) hideFromAccessibility()
            }
    ) {
        content()
    }
}

@Composable
private fun HomeTabPane(
    navController: NavHostController,
    container: VelunaAppContainer,
    homeSearchVm: HomeSearchViewModel,
    libraryStatsVm: LibraryStatsViewModel,
    playbackState: State<PlaybackUiState>,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenEditHome: () -> Unit
) {
    val context = LocalContext.current
    val homeState by homeSearchVm.homeState.collectAsStateWithLifecycle()
    val shortcuts by homeSearchVm.shortcuts.collectAsStateWithLifecycle()
    val jumpBack by homeSearchVm.recentJumpBack.collectAsStateWithLifecycle()
    val familiarArtists by homeSearchVm.familiarArtists.collectAsStateWithLifecycle()
    val prefs by libraryStatsVm.prefs.collectAsStateWithLifecycle()
    val likedIds by libraryStatsVm.likedVideoIds.collectAsStateWithLifecycle()
    val cloudAuthState by container.cloudSyncCoordinator.authState.collectAsStateWithLifecycle()

    val nowPlayingSong by remember(playbackState) {
        derivedStateOf { playbackState.value.nowPlaying }
    }
    val artworkAccent by remember(playbackState) {
        derivedStateOf { playbackState.value.artworkAccentColor }
    }

    val onSignInWithGoogle = remember(context, container) { { container.cloudSyncCoordinator.signIn(context) } }
    val onOpenAccountSettings = remember(onNavigateTopTab) { { onNavigateTopTab(VelunaTopTab.SETTINGS) } }
    val selectedChipParams by remember { derivedStateOf { homeState.selectedChipParams } }
    val onRetry = remember(homeSearchVm) {
        { homeSearchVm.loadHome(selectedChipParams) }
    }
    val onToggleLike = remember(container) { { song: SongItem -> container.playerController.toggleLike(song) } }
    val onOpenHistory = remember(navController) { { navController.navigate(HistoryRoute) } }

    HomeScreen(
        homeState = homeState,
        prefs = prefs,
        shortcuts = shortcuts,
        jumpBack = jumpBack,
        familiarArtists = familiarArtists,
        nowPlayingSong = nowPlayingSong,
        artworkAccent = artworkAccent,
        likedIds = likedIds,
        cloudAuthState = cloudAuthState,
        onSignInWithGoogle = onSignInWithGoogle,
        onOpenAccountSettings = onOpenAccountSettings,
        onSelectChip = homeSearchVm::loadHome,
        onRetry = onRetry,
        onOpenBrowseItem = onOpenBrowseItem,
        onTrackMore = onTrackMore,
        onToggleLike = onToggleLike,
        onOpenHistory = onOpenHistory,
        onOpenEditHome = onOpenEditHome
    )
}

@Composable
private fun SearchTabPane(
    activeState: State<Boolean>,
    homeSearchVm: HomeSearchViewModel,
    playbackState: State<PlaybackUiState>,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit
) {
    val active = activeState.value
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) {
        if (!active) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    val searchState by homeSearchVm.searchState.collectAsStateWithLifecycle()
    val history by homeSearchVm.searchHistory.collectAsStateWithLifecycle()
    val nowPlayingId by remember(playbackState) {
        derivedStateOf { playbackState.value.nowPlaying?.videoId }
    }
    val onOpenMoodTile = remember(homeSearchVm, onNavigateTopTab) {
        { tile: MoodTile ->
            homeSearchVm.openMoodTile(tile)
            onNavigateTopTab(VelunaTopTab.HOME)
        }
    }

    SearchScreen(
        state = searchState,
        searchHistory = history,
        nowPlayingId = nowPlayingId,
        onQueryChange = homeSearchVm::onQueryChange,
        onSubmitSearch = homeSearchVm::submitSearch,
        onClearSearch = homeSearchVm::clearSearchResults,
        onSelectCategory = homeSearchVm::selectSearchCategory,
        onRemoveHistory = homeSearchVm::removeSearchHistoryItem,
        onClearHistory = homeSearchVm::clearSearchHistory,
        onOpenBrowseItem = onOpenBrowseItem,
        onTrackMore = onTrackMore,
        onOpenMoodTile = onOpenMoodTile
    )
}

@Composable
private fun LibraryTabPane(
    container: VelunaAppContainer,
    libraryStatsVm: LibraryStatsViewModel,
    playbackState: State<PlaybackUiState>,
    downloadsFlow: StateFlow<Map<String, DownloadProgress>>,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onOpenImportPlaylist: () -> Unit
) {
    val playlists by libraryStatsVm.playlists.collectAsStateWithLifecycle()
    val likedSongs by libraryStatsVm.likedSongs.collectAsStateWithLifecycle()
    val savedAlbums by libraryStatsVm.savedAlbums.collectAsStateWithLifecycle()
    val followedArtists by libraryStatsVm.followedArtists.collectAsStateWithLifecycle()
    val downloadedTracks by libraryStatsVm.downloadedTracks.collectAsStateWithLifecycle()
    val localTracks by libraryStatsVm.localTracks.collectAsStateWithLifecycle()
    val activeDownloads by downloadsFlow.collectAsStateWithLifecycle()
    val nowPlayingId by remember(playbackState) {
        derivedStateOf { playbackState.value.nowPlaying?.videoId }
    }
    val onToggleLike = remember(container) { { song: SongItem -> container.playerController.toggleLike(song) } }
    val onRetryDownload = remember(container) {
        { prog: DownloadProgress ->
            container.playerController.downloadTrack(
                SongItem(
                    videoId = prog.videoId,
                    title = prog.title,
                    artists = prog.artists,
                    thumbnail = prog.thumbnail
                )
            )
        }
    }

    LibraryScreen(
        playlists = playlists,
        likedSongs = likedSongs,
        savedAlbums = savedAlbums,
        followedArtists = followedArtists,
        downloads = downloadedTracks,
        activeDownloads = activeDownloads,
        localTracks = localTracks,
        nowPlayingId = nowPlayingId,
        onOpenBrowseItem = onOpenBrowseItem,
        onPlaySongs = onPlayTracks,
        onTrackMore = onTrackMore,
        onToggleLike = onToggleLike,
        onOpenCreatePlaylist = onOpenCreatePlaylist,
        onOpenImportPlaylist = onOpenImportPlaylist,
        onImportLocalFiles = libraryStatsVm::importLocalFiles,
        onImportLocalFolder = libraryStatsVm::importLocalFolder,
        onRemoveLocalTrack = libraryStatsVm::removeLocalTrack,
        onDeleteDownload = container.playerController::deleteDownload,
        onRetryDownload = onRetryDownload,
        onDismissDownloadStatus = container.playerController::dismissDownloadStatus
    )
}

@Composable
private fun StatsTabPane(
    navController: NavHostController,
    container: VelunaAppContainer,
    libraryStatsVm: LibraryStatsViewModel,
    playbackState: State<PlaybackUiState>,
    ensureNotificationPermission: () -> Unit,
    onTrackMore: (SongItem) -> Unit
) {
    val statsState by libraryStatsVm.statsState.collectAsStateWithLifecycle()
    val nowPlayingId by remember(playbackState) {
        derivedStateOf { playbackState.value.nowPlaying?.videoId }
    }
    val onOpenHistory = remember(navController) { { navController.navigate(HistoryRoute) } }
    val onPlaySong = remember(ensureNotificationPermission, container) {
        { song: SongItem ->
            ensureNotificationPermission()
            container.playerController.playSong(song, "Top Songs")
        }
    }
    val onOpenArtist = remember(navController) { { id: String -> navController.navigate(ArtistRoute(id)) } }
    val onOpenAlbum = remember(navController) { { id: String -> navController.navigate(AlbumRoute(id)) } }

    StatsScreen(
        state = statsState,
        nowPlayingId = nowPlayingId,
        onSelectRange = libraryStatsVm::refreshStats,
        onOpenHistory = onOpenHistory,
        onPlaySong = onPlaySong,
        onTrackMore = onTrackMore,
        onOpenArtist = onOpenArtist,
        onOpenAlbum = onOpenAlbum
    )
}

@Composable
private fun SettingsTabPane(
    container: VelunaAppContainer,
    homeSearchVm: HomeSearchViewModel,
    libraryStatsVm: LibraryStatsViewModel,
    onOpenTasteOnboarding: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val context = LocalContext.current
    val prefs by libraryStatsVm.prefs.collectAsStateWithLifecycle()
    val tasteProfile by homeSearchVm.tasteProfile.collectAsStateWithLifecycle()
    val cloudAuthState by container.cloudSyncCoordinator.authState.collectAsStateWithLifecycle()
    val blockedArtists by libraryStatsVm.blockedArtists.collectAsStateWithLifecycle()
    val scrobblePendingCount by libraryStatsVm.scrobblePendingCount.collectAsStateWithLifecycle()
    val lastScrobbleError by libraryStatsVm.lastScrobbleError.collectAsStateWithLifecycle()
    val restoredLegacyBackupWarning by libraryStatsVm.restoredLegacyBackupWarning.collectAsStateWithLifecycle()

    val onSignInWithGoogle = remember(context, container) { { container.cloudSyncCoordinator.signIn(context) } }
    val onSignOut = remember(container) { { container.cloudSyncCoordinator.signOut() } }
    val onConfirmSignOutDiscard = remember(container) { { container.cloudSyncCoordinator.confirmSignOutDiscard() } }
    val onDismissSignOutConfirm = remember(container) { { container.cloudSyncCoordinator.dismissSignOutConfirm() } }
    val onDeleteAccount = remember(context, container) { { container.cloudSyncCoordinator.deleteAccount(context) } }
    val onClearDeleteAccountError = remember(container) { { container.cloudSyncCoordinator.clearDeleteAccountError() } }
    val onSyncNow = remember(container) { { container.cloudSyncCoordinator.syncNow() } }
    val onMergeUnownedData = remember(container) { { container.cloudSyncCoordinator.resolveUnownedDataMergeIntoCurrentAccount() } }
    val onDiscardUnownedData = remember(container) { { container.cloudSyncCoordinator.resolveUnownedDataDiscardAndSyncAccount() } }

    SettingsScreen(
        prefs = prefs,
        blockedArtists = blockedArtists,
        tasteProfile = tasteProfile,
        cloudAuthState = cloudAuthState,
        scrobblePendingCount = scrobblePendingCount,
        lastScrobbleError = lastScrobbleError,
        restoredLegacyBackupWarning = restoredLegacyBackupWarning,
        onDismissLegacyBackupWarning = libraryStatsVm::dismissLegacyBackupWarning,
        onSignInWithGoogle = onSignInWithGoogle,
        onSignOut = onSignOut,
        onConfirmSignOutDiscard = onConfirmSignOutDiscard,
        onDismissSignOutConfirm = onDismissSignOutConfirm,
        onDeleteAccount = onDeleteAccount,
        onClearDeleteAccountError = onClearDeleteAccountError,
        onSyncNow = onSyncNow,
        onMergeUnownedData = onMergeUnownedData,
        onDiscardUnownedData = onDiscardUnownedData,
        onOpenTasteOnboarding = onOpenTasteOnboarding,
        onSetThemePreset = libraryStatsVm::setThemePreset,
        onSetThemeMode = libraryStatsVm::setThemeMode,
        onSetCustomAccent = libraryStatsVm::setCustomAccent,
        onSetAdaptToArtwork = libraryStatsVm::setAdaptToArtwork,
        onSetClassicPlayerLayout = libraryStatsVm::setClassicPlayerLayout,
        onSetCornerRadius = libraryStatsVm::setCornerRadius,
        onSetDisplayName = libraryStatsVm::setDisplayName,
        onSetAudioQuality = libraryStatsVm::setAudioQuality,
        onSetAutoplay = libraryStatsVm::setAutoplay,
        onSetLoudnessNormalization = libraryStatsVm::setLoudnessNormalization,
        onSetHideMusicVideos = libraryStatsVm::setHideMusicVideos,
        onSetAllowMirrorFallbacks = libraryStatsVm::setAllowMirrorFallbacks,
        onOpenEqualizer = onOpenEqualizer,
        onUpdateLyricsProviders = libraryStatsVm::updateLyricsProviders,
        onSaveScrobbling = libraryStatsVm::setScrobbling,
        onUnblockArtist = libraryStatsVm::unblockArtist,
        onBuildBackupJson = libraryStatsVm::buildBackupJson,
        onRestoreBackupJson = libraryStatsVm::restoreBackupJson,
        onStatusMessage = libraryStatsVm::postStatus
    )
}
