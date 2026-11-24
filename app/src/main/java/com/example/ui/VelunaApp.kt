package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.VelunaAppContainer
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.SongItem
import com.example.ui.components.MiniPlayerBar
import com.example.ui.navigation.AlbumRoute
import com.example.ui.navigation.ArtistRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.PlaylistRoute
import com.example.ui.navigation.VelunaTopTab
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.VelunaTheme
import com.example.ui.viewmodel.DetailViewModel
import com.example.ui.viewmodel.HomeSearchViewModel
import com.example.ui.viewmodel.LibraryStatsViewModel
import com.example.ui.viewmodel.NowPlayingViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun VelunaApp(
    container: VelunaAppContainer,
    useNavigationRail: Boolean
) {
    val context = LocalContext.current

    val homeSearchVm: HomeSearchViewModel = viewModel(
        factory = HomeSearchViewModel.Factory(
            container.innerTubeClient,
            container.repository,
            container.prefsRepository,
            container.tasteEngine
        )
    )
    val detailVm: DetailViewModel = viewModel(
        factory = DetailViewModel.Factory(
            container.innerTubeClient,
            container.repository,
            container.tasteEngine
        )
    )
    val nowPlayingVm: NowPlayingViewModel = viewModel(
        factory = NowPlayingViewModel.Factory(
            container.playerController,
            container.lyricsRepository,
            container.innerTubeClient,
            container.prefsRepository
        )
    )
    val libraryStatsVm: LibraryStatsViewModel = viewModel(
        factory = LibraryStatsViewModel.Factory(
            container.repository,
            container.prefsRepository,
            container.tasteEngine,
            container.scrobbleClient
        )
    )

    val prefs by libraryStatsVm.prefs.collectAsStateWithLifecycle()
    val playbackFlow = container.playerController.state
    val downloadsFlow = container.playerController.downloads
    val playbackState = playbackFlow.collectAsStateWithLifecycle()
    val artworkAccent by remember(playbackState) {
        derivedStateOf { playbackState.value.artworkAccentColor }
    }
    val hasNowPlaying by remember(playbackState) {
        derivedStateOf { playbackState.value.nowPlaying != null }
    }
    val downloadedTracksState = libraryStatsVm.downloadedTracks.collectAsStateWithLifecycle()
    val playlists by libraryStatsVm.playlists.collectAsStateWithLifecycle()
    val statusBanner by libraryStatsVm.statusBanner.collectAsStateWithLifecycle()
    val likedIds by libraryStatsVm.likedVideoIds.collectAsStateWithLifecycle()
    val tasteProfile by homeSearchVm.tasteProfile.collectAsStateWithLifecycle()
    val cloudAuthState by container.cloudSyncCoordinator.authState.collectAsStateWithLifecycle()

    val downloadedIds by remember(downloadedTracksState) {
        derivedStateOf { downloadedTracksState.value.mapTo(HashSet()) { it.videoId } }
    }

    LaunchedEffect(Unit) {
        container.cloudSyncCoordinator.attemptAutoSignInOnLaunch(context)
    }

    val ensureNotificationPermission = rememberNotificationPermissionRequester()

    VelunaTheme(
        prefs = prefs,
        artworkAccent = artworkAccent
    ) {
        val navController = rememberNavController()
        val backStackEntryState = navController.currentBackStackEntryAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(statusBanner) {
            val msg = statusBanner
            if (!msg.isNullOrBlank()) {
                snackbarHostState.showSnackbar(msg)
                libraryStatsVm.consumeStatusBanner()
            }
        }
        LaunchedEffect(cloudAuthState.errorMessage) {
            val err = cloudAuthState.errorMessage
            if (!err.isNullOrBlank()) {
                snackbarHostState.showSnackbar(err)
                container.cloudSyncCoordinator.clearError()
            }
        }
        LaunchedEffect(container.playerController) {
            container.playerController.events.collect { msg ->
                snackbarHostState.showSnackbar(msg)
            }
        }

        var menuTrack by remember { mutableStateOf<SongItem?>(null) }
        var addToPlaylistTrack by remember { mutableStateOf<SongItem?>(null) }
        var showCreatePlaylist by rememberSaveable { mutableStateOf(false) }
        var showImportPlaylist by rememberSaveable { mutableStateOf(false) }
        var showSleepTimer by rememberSaveable { mutableStateOf(false) }
        var showEqualizer by rememberSaveable { mutableStateOf(false) }
        var showEditHome by rememberSaveable { mutableStateOf(false) }
        var showTasteEditor by rememberSaveable { mutableStateOf(false) }

        val playTracks = remember(ensureNotificationPermission, container) {
            { tracks: List<SongItem>, index: Int, source: String ->
                ensureNotificationPermission()
                container.playerController.playQueue(tracks, index, source)
            }
        }

        val openBrowseItem = remember(ensureNotificationPermission, container, navController) {
            { item: BrowseItem ->
                when (item.kind) {
                    BrowseKind.SONG -> {
                        ensureNotificationPermission()
                        container.playerController.playSong(item.toSongItem(), item.title)
                    }
                    BrowseKind.ALBUM -> {
                        container.playerController.openNowPlaying(false)
                        navController.navigate(AlbumRoute(item.id))
                    }
                    BrowseKind.ARTIST -> {
                        container.playerController.openNowPlaying(false)
                        navController.navigate(ArtistRoute(item.id))
                    }
                    BrowseKind.PLAYLIST -> {
                        container.playerController.openNowPlaying(false)
                        navController.navigate(PlaylistRoute(item.id))
                    }
                }
            }
        }

        val tabNavState = rememberVelunaTabNavState()

        val navigateTopTab = remember(libraryStatsVm, navController, tabNavState) {
            { tab: VelunaTopTab ->
                tabNavState.selectTab(tab)
                if (tab == VelunaTopTab.STATS) libraryStatsVm.refreshStats()
                if (navController.currentDestination?.hasRoute<HomeRoute>() != true) {
                    navController.popBackStack<HomeRoute>(inclusive = false)
                }
            }
        }

        val onTrackMore = remember { { song: SongItem -> menuTrack = song } }
        val onOpenEditHome = remember { { showEditHome = true } }
        val onOpenCreatePlaylist = remember { { showCreatePlaylist = true } }
        val onOpenImportPlaylist = remember(libraryStatsVm) {
            {
                libraryStatsVm.resetImportState()
                showImportPlaylist = true
            }
        }
        val onOpenTasteOnboarding = remember { { showTasteEditor = true } }
        val onOpenEqualizer = remember { { showEqualizer = true } }

        val isAtTopTabRootState = remember(backStackEntryState) {
            derivedStateOf {
                val dest = backStackEntryState.value?.destination
                dest == null || dest.hasRoute<HomeRoute>()
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    contentWindowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    ),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!useNavigationRail) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                AnimatedVisibility(
                                    visible = hasNowPlaying,
                                    enter = slideInVertically(VelunaMotion.expressiveIntOffsetSpring()) { it / 2 } + fadeIn(VelunaMotion.fastFade()),
                                    exit = slideOutVertically(VelunaMotion.snappyIntOffsetSpring()) { it / 2 } + fadeOut(VelunaMotion.fastFade())
                                ) {
                                    MiniPlayerBar(
                                        playbackFlow = playbackFlow,
                                        positionFlow = container.playerController.positionState,
                                        likedIds = likedIds,
                                        onOpenNowPlaying = { container.playerController.openNowPlaying(true) },
                                        onTogglePlayPause = { container.playerController.togglePlayPause() },
                                        onSkipNext = { container.playerController.skipNext() },
                                        onToggleLike = { container.playerController.toggleLikeCurrent() }
                                    )
                                }
                                VelunaBottomNavBar(selectedTab = tabNavState.selectedTab, onSelectTab = navigateTopTab)
                            }
                        } else {
                            AnimatedVisibility(
                                visible = hasNowPlaying,
                                enter = slideInVertically(VelunaMotion.expressiveIntOffsetSpring()) { it } + fadeIn(VelunaMotion.fastFade()),
                                exit = slideOutVertically(VelunaMotion.snappyIntOffsetSpring()) { it } + fadeOut(VelunaMotion.fastFade())
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    MiniPlayerBar(
                                        playbackFlow = playbackFlow,
                                        positionFlow = container.playerController.positionState,
                                        likedIds = likedIds,
                                        onOpenNowPlaying = { container.playerController.openNowPlaying(true) },
                                        onTogglePlayPause = { container.playerController.togglePlayPause() },
                                        onSkipNext = { container.playerController.skipNext() },
                                        onToggleLike = { container.playerController.toggleLikeCurrent() }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useNavigationRail) {
                            VelunaNavRail(
                                selectedTab = tabNavState.selectedTab,
                                onSelectTab = navigateTopTab,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .statusBarsPadding()
                            )
                        }

                        VelunaNavHost(
                            navController = navController,
                            tabNavState = tabNavState,
                            isAtTopTabRootState = isAtTopTabRootState,
                            container = container,
                            homeSearchVm = homeSearchVm,
                            detailVm = detailVm,
                            libraryStatsVm = libraryStatsVm,
                            playbackState = playbackState,
                            downloadsFlow = downloadsFlow,
                            ensureNotificationPermission = ensureNotificationPermission,
                            onNavigateTopTab = navigateTopTab,
                            onPlayTracks = playTracks,
                            onOpenBrowseItem = openBrowseItem,
                            onTrackMore = onTrackMore,
                            onOpenEditHome = onOpenEditHome,
                            onOpenCreatePlaylist = onOpenCreatePlaylist,
                            onOpenImportPlaylist = onOpenImportPlaylist,
                            onOpenTasteOnboarding = onOpenTasteOnboarding,
                            onOpenEqualizer = onOpenEqualizer,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }

                VelunaGlobalOverlaysAndDialogs(
                    container = container,
                    navController = navController,
                    homeSearchVm = homeSearchVm,
                    nowPlayingVm = nowPlayingVm,
                    libraryStatsVm = libraryStatsVm,
                    prefs = prefs,
                    playbackFlow = playbackFlow,
                    playlists = playlists,
                    likedIds = likedIds,
                    downloadedIds = downloadedIds,
                    tasteProfile = tasteProfile,
                    ensureNotificationPermission = ensureNotificationPermission,
                    menuTrack = menuTrack,
                    onOpenMenuTrack = { menuTrack = it },
                    onDismissMenuTrack = { menuTrack = null },
                    addToPlaylistTrack = addToPlaylistTrack,
                    onOpenAddToPlaylist = { addToPlaylistTrack = it },
                    onDismissAddToPlaylist = { addToPlaylistTrack = null },
                    showCreatePlaylist = showCreatePlaylist,
                    onDismissCreatePlaylist = { showCreatePlaylist = false },
                    showImportPlaylist = showImportPlaylist,
                    onDismissImportPlaylist = { showImportPlaylist = false },
                    showSleepTimer = showSleepTimer,
                    onOpenSleepTimer = { showSleepTimer = true },
                    onDismissSleepTimer = { showSleepTimer = false },
                    showEqualizer = showEqualizer,
                    onOpenEqualizer = { showEqualizer = true },
                    onDismissEqualizer = { showEqualizer = false },
                    showEditHome = showEditHome,
                    onDismissEditHome = { showEditHome = false },
                    showTasteEditor = showTasteEditor,
                    onDismissTasteEditor = { showTasteEditor = false }
                )
            }
        }
    }
}
