package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserPrefsRepository
import com.example.data.network.InnerTubeClient
import com.example.data.repository.TasteEngine
import com.example.data.repository.VelunaRepository
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.HistoryGroup
import com.example.model.HomePage
import com.example.model.MoodSection
import com.example.model.MoodTile
import com.example.model.SearchResults
import com.example.model.SearchSuggestions
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.util.SecurityValidators
import com.example.util.suspendRunCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedChipParams: String? = null,
    val homePage: HomePage = InnerTubeClient.fallbackDiscoveryHome(),
    val moodsTitle: String? = null
)

@Immutable
data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val error: String? = null,
    val results: SearchResults? = null,
    val suggestions: SearchSuggestions = SearchSuggestions(),
    val moodsSections: List<MoodSection> = emptyList(),
    val isMoodsLoading: Boolean = false,
    val selectedCategoryTab: Int = 0
)

@Immutable
data class OnboardingSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val artists: List<BrowseItem> = emptyList(),
    val songs: List<BrowseItem> = emptyList()
)

class HomeSearchViewModel(
    private val innerTube: InnerTubeClient,
    private val repository: VelunaRepository,
    private val prefsRepo: UserPrefsRepository,
    private val tasteEngine: TasteEngine? = null
) : ViewModel() {

    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _onboardingSearch = MutableStateFlow(OnboardingSearchUiState())
    val onboardingSearch: StateFlow<OnboardingSearchUiState> = _onboardingSearch.asStateFlow()

    // Initial null avoids flashing onboarding before DataStore loads from disk
    val tasteProfile: StateFlow<TasteProfile?> = prefsRepo.tasteProfileFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val shortcuts: StateFlow<List<BrowseItem>> = repository.shortcutsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchHistory: StateFlow<List<String>> = repository.searchHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val historyGroups: StateFlow<List<HistoryGroup>> = repository.observeHistoryGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentJumpBack: StateFlow<List<BrowseItem>> = combine(
        historyGroups,
        repository.blockedArtistsFlow
    ) { groups, blocked ->
        val blockedSet = blocked.map { it.name.lowercase() }.toHashSet()
        groups.flatMap { it.items }
            .map { it.second }
            .filter { song ->
                val full = song.artists.trim().lowercase()
                if (full in blockedSet) return@filter false
                song.artists.split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .none { it in blockedSet }
            }
            .distinctBy { it.videoId }
            .take(12)
            .map { song ->
                BrowseItem(
                    kind = BrowseKind.SONG,
                    id = song.videoId,
                    title = song.title,
                    subtitle = song.artists,
                    thumbnail = song.thumbnail,
                    duration = song.duration,
                    albumId = song.albumId,
                    artistRuns = song.artistRuns,
                    explicit = song.explicit,
                    isVideo = song.isVideo
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val familiarArtists: StateFlow<List<BrowseItem>> = combine(
        historyGroups,
        repository.followedArtistsFlow,
        repository.blockedArtistsFlow
    ) { groups, followed, blocked ->
        val blockedSet = blocked.map { it.name.lowercase() }.toHashSet()
        val fromHistory = groups.flatMap { it.items }
            .map { it.second }
            .mapNotNull { s ->
                val artistId = s.artistId ?: s.artistRuns.firstOrNull { it.id != null }?.id ?: return@mapNotNull null
                val name = s.artists.split(",").firstOrNull()?.trim().orEmpty()
                if (name.isBlank() || name.lowercase() in blockedSet) return@mapNotNull null
                BrowseItem(
                    kind = BrowseKind.ARTIST,
                    id = artistId,
                    title = name,
                    subtitle = "Recent favorite",
                    thumbnail = s.thumbnail
                )
            }
        (followed + fromHistory)
            .distinctBy { it.id }
            .take(10)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var homeJob: Job? = null
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var onboardingSearchJob: Job? = null

    init {
        loadHome(null)
        loadMoods()
    }

    fun loadHome(params: String?) {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            _homeState.update {
                it.copy(isLoading = true, error = null, selectedChipParams = params, moodsTitle = null)
            }
            innerTube.getHome(params).onSuccess { remotePage ->
                val personalized = suspendRunCatching {
                    tasteEngine?.personalizeHomePage(remotePage, isFilteredChipOrMood = !params.isNullOrBlank())
                }.getOrNull() ?: remotePage

                _homeState.update {
                    it.copy(
                        isLoading = false,
                        homePage = personalized.copy(
                            chips = personalized.chips.ifEmpty { InnerTubeClient.fallbackDiscoveryHome().chips }
                        ),
                        error = null
                    )
                }
            }.onFailure { err ->
                // Even if FEmusic_home fails temporarily, try building personalized shelves from seeds
                val seedFallback = suspendRunCatching {
                    tasteEngine?.personalizeHomePage(InnerTubeClient.fallbackDiscoveryHome(), isFilteredChipOrMood = false)
                }.getOrNull()
                if (seedFallback != null && seedFallback.sections.isNotEmpty()) {
                    _homeState.update {
                        it.copy(
                            isLoading = false,
                            homePage = seedFallback.copy(
                                chips = seedFallback.chips.ifEmpty { InnerTubeClient.fallbackDiscoveryHome().chips }
                            ),
                            error = null
                        )
                    }
                } else {
                    _homeState.update {
                        it.copy(
                            isLoading = false,
                            error = SecurityValidators.safeUserErrorMessage(err, "Unable to reach YouTube Music")
                        )
                    }
                }
            }
        }
    }

    fun openMoodTile(tile: MoodTile) {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            _homeState.update { it.copy(isLoading = true, error = null, moodsTitle = tile.title) }
            innerTube.getMoodCategory(tile.params).onSuccess { page ->
                _homeState.update { it.copy(isLoading = false, homePage = page) }
            }.onFailure { err ->
                _homeState.update {
                    it.copy(
                        isLoading = false,
                        error = SecurityValidators.safeUserErrorMessage(err, "Failed to load mood category")
                    )
                }
            }
        }
    }

    fun loadMoods() {
        viewModelScope.launch {
            _searchState.update { it.copy(isMoodsLoading = true) }
            innerTube.getMoodsAndGenres().onSuccess { sections ->
                _searchState.update { it.copy(moodsSections = sections, isMoodsLoading = false) }
            }.onFailure {
                _searchState.update { it.copy(isMoodsLoading = false) }
            }
        }
    }

    fun onOnboardingQueryChange(q: String) {
        _onboardingSearch.update { it.copy(query = q) }
        onboardingSearchJob?.cancel()
        val trimmed = q.trim()
        if (trimmed.length < 2) {
            _onboardingSearch.update { it.copy(isSearching = false, artists = emptyList(), songs = emptyList()) }
            return
        }
        onboardingSearchJob = viewModelScope.launch {
            delay(260L)
            _onboardingSearch.update { it.copy(isSearching = true) }
            innerTube.searchAll(trimmed).onSuccess { res ->
                val foundArtists = (res.artists + res.top.filter { it.kind == BrowseKind.ARTIST })
                    .distinctBy { it.id }
                    .take(10)
                val foundSongs = (res.songs + res.top.filter { it.kind == BrowseKind.SONG })
                    .distinctBy { it.id }
                    .take(12)
                _onboardingSearch.update {
                    it.copy(
                        isSearching = false,
                        artists = foundArtists,
                        songs = foundSongs
                    )
                }
            }.onFailure {
                _onboardingSearch.update { it.copy(isSearching = false) }
            }
        }
    }

    fun saveOnboardingTaste(
        genres: List<String>,
        artists: List<TasteSeed>,
        songs: List<TasteSeed>,
        onFinished: () -> Unit = {}
    ) {
        viewModelScope.launch {
            if (tasteEngine != null) {
                tasteEngine.applyOnboardingSeeds(genres, artists, songs)
            } else {
                prefsRepo.setOnboardingCompleted(true)
            }
            loadHome(null)
            onFinished()
        }
    }

    fun skipOnboarding(onFinished: () -> Unit = {}) {
        viewModelScope.launch {
            if (tasteEngine != null) {
                tasteEngine.skipOnboarding()
            } else {
                prefsRepo.setOnboardingCompleted(true)
            }
            onFinished()
        }
    }

    fun onQueryChange(q: String) {
        if (q == _searchState.value.query) return
        _searchState.update { it.copy(query = q, results = null, error = null) }
        suggestJob?.cancel()
        if (q.isBlank()) {
            searchJob?.cancel()
            _searchState.update {
                it.copy(isSearching = false, suggestions = SearchSuggestions(), results = null, error = null)
            }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(260L)
            innerTube.searchSuggestions(q).onSuccess { sug ->
                _searchState.update { it.copy(suggestions = sug) }
            }
        }
    }

    fun submitSearch(queryOverride: String? = null) {
        val q = (queryOverride ?: _searchState.value.query).trim()
        if (q.isBlank()) return
        suggestJob?.cancel()
        searchJob?.cancel()
        _searchState.update { it.copy(query = q, isSearching = true, results = null, error = null) }
        searchJob = viewModelScope.launch {
            repository.recordSearchQuery(q)
            innerTube.searchAll(q).onSuccess { res ->
                _searchState.update {
                    it.copy(isSearching = false, results = res, error = null)
                }
            }.onFailure { err ->
                _searchState.update {
                    it.copy(
                        isSearching = false,
                        error = SecurityValidators.safeUserErrorMessage(err, "Search failed")
                    )
                }
            }
        }
    }

    fun selectSearchCategory(tabIndex: Int) {
        _searchState.update { it.copy(selectedCategoryTab = tabIndex) }
    }

    fun clearSearchResults() {
        suggestJob?.cancel()
        searchJob?.cancel()
        _searchState.update {
            it.copy(query = "", isSearching = false, results = null, suggestions = SearchSuggestions(), error = null)
        }
    }

    fun removeSearchHistoryItem(query: String) {
        viewModelScope.launch { repository.removeSearchQuery(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { repository.clearSearchHistory() }
    }

    fun addShortcut(item: BrowseItem) {
        viewModelScope.launch { repository.toggleShortcut(item) }
    }

    fun removeShortcut(id: String) {
        viewModelScope.launch { repository.removeShortcut(id) }
    }

    fun addSongToQuickPlaylistOrLike(song: SongItem) {
        viewModelScope.launch { repository.toggleLike(song) }
    }

    class Factory(
        private val innerTube: InnerTubeClient,
        private val repository: VelunaRepository,
        private val prefsRepo: UserPrefsRepository,
        private val tasteEngine: TasteEngine? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeSearchViewModel(innerTube, repository, prefsRepo, tasteEngine) as T
    }
}
