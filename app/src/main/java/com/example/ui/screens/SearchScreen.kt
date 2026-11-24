package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.MoodTile
import com.example.model.SongItem
import com.example.ui.components.MoodGridTile
import com.example.ui.components.SectionHeading
import com.example.ui.components.TrackRow
import com.example.ui.components.VelunaArtwork
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.rememberVelunaLazyListState
import com.example.ui.theme.velunaOutlinedFieldColors
import com.example.ui.viewmodel.SearchUiState

private enum class SearchContentMode { LOADING, RESULTS, ERROR, SUGGESTIONS, DISCOVER }

@Composable
fun SearchScreen(
    state: SearchUiState,
    searchHistory: List<String>,
    nowPlayingId: String?,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onClearSearch: () -> Unit,
    onSelectCategory: (Int) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenMoodTile: (MoodTile) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val smoothFling = rememberVelunaFlingBehavior()
    val resultsListState = rememberVelunaLazyListState()
    val suggestionsListState = rememberVelunaLazyListState()
    val discoverListState = rememberVelunaLazyListState()
    val contentMode = when {
        state.isSearching -> SearchContentMode.LOADING
        state.results != null -> SearchContentMode.RESULTS
        !state.error.isNullOrBlank() && state.query.isNotBlank() -> SearchContentMode.ERROR
        state.query.isNotBlank() -> SearchContentMode.SUGGESTIONS
        else -> SearchContentMode.DISCOVER
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.size(48.dp).testTag("search_clear_btn")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                colors = velunaOutlinedFieldColors(),
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        onSubmitSearch(null)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("search_input")
            )

            AnimatedContent(
                targetState = contentMode,
                transitionSpec = {
                    fadeIn(VelunaMotion.tabTransitionTween()) togetherWith fadeOut(VelunaMotion.tabFadeOutTween())
                },
                modifier = Modifier.weight(1f),
                label = "search_content_mode"
            ) { mode ->
            when (mode) {
                SearchContentMode.LOADING -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                SearchContentMode.RESULTS -> {
                    val results = state.results ?: return@AnimatedContent
                    Column(modifier = Modifier.fillMaxSize()) {
                    val tabs = listOf(
                        "top" to stringResource(R.string.search_tab_top),
                        "songs" to stringResource(R.string.search_tab_songs),
                        "videos" to stringResource(R.string.search_tab_videos),
                        "albums" to stringResource(R.string.search_tab_albums),
                        "artists" to stringResource(R.string.search_tab_artists),
                        "playlists" to stringResource(R.string.search_tab_playlists)
                    )
                    ScrollableTabRow(
                        selectedTabIndex = state.selectedCategoryTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        edgePadding = 12.dp,
                        indicator = { tabPositions ->
                            if (state.selectedCategoryTab < tabPositions.size) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[state.selectedCategoryTab]),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    ) {
                        tabs.forEachIndexed { idx, (key, label) ->
                            Tab(
                                selected = state.selectedCategoryTab == idx,
                                onClick = { onSelectCategory(idx) },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text(label, maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("search_tab_$key")
                            )
                        }
                    }

                    val list = remember(results, state.selectedCategoryTab) {
                        when (state.selectedCategoryTab) {
                            0 -> results.top + results.songs.take(6) + results.albums.take(4) + results.artists.take(4)
                            1 -> results.songs
                            2 -> results.videos
                            3 -> results.albums
                            4 -> results.artists
                            else -> results.playlists
                        }.distinctBy { "${it.kind}_${it.id}" }
                    }

                    if (list.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.search_no_results, tabs[state.selectedCategoryTab].second),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = resultsListState,
                            flingBehavior = smoothFling,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(
                                items = list,
                                key = { idx, item -> "${idx}_${item.kind}_${item.id}" },
                                contentType = { _, item -> item.kind }
                            ) { _, item ->
                                if (item.kind == BrowseKind.SONG) {
                                    val song = remember(item) { item.toSongItem() }
                                    TrackRow(
                                        song = song,
                                        isPlaying = nowPlayingId == song.videoId,
                                        onClick = { onOpenBrowseItem(item) },
                                        onMoreClick = { onTrackMore(song) }
                                    )
                                } else {
                                    ListItem(
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent,
                                            headlineColor = MaterialTheme.colorScheme.onSurface,
                                            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = {
                                            Text(
                                                listOfNotNull(item.kind.name.lowercase().replaceFirstChar { it.uppercase() }, item.subtitle).joinToString(" • "),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingContent = {
                                            VelunaArtwork(
                                                url = item.thumbnail,
                                                contentDescription = item.title,
                                                isCircle = item.kind == BrowseKind.ARTIST,
                                                targetSizePx = 180,
                                                modifier = Modifier.size(48.dp)
                                            )
                                        },
                                        modifier = Modifier
                                            .clickable { onOpenBrowseItem(item) }
                                            .testTag("search_result_${item.id}")
                                    )
                                }
                            }
                        }
                    }
                    }
                }

                SearchContentMode.ERROR -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.search_error_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = state.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { onSubmitSearch(null) },
                                    modifier = Modifier.testTag("search_retry_btn")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(stringResource(R.string.action_retry), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }

                SearchContentMode.SUGGESTIONS -> {
                    LazyColumn(
                        state = suggestionsListState,
                        flingBehavior = smoothFling,
                        contentPadding = PaddingValues(bottom = 28.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = state.suggestions.queries,
                            key = { idx, q -> "sug_${idx}_$q" },
                            contentType = { _, _ -> "suggestion_query" }
                        ) { _, suggestion ->
                            ListItem(
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                    headlineColor = MaterialTheme.colorScheme.onSurface,
                                    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                headlineContent = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingContent = {
                                    IconButton(
                                        onClick = { onQueryChange(suggestion) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.NorthWest, contentDescription = stringResource(R.string.search_fill_query))
                                    }
                                },
                                modifier = Modifier
                                    .clickable {
                                        keyboard?.hide()
                                        onSubmitSearch(suggestion)
                                    }
                                    .testTag("search_suggestion_$suggestion")
                            )
                        }
                        itemsIndexed(
                            items = state.suggestions.items,
                            key = { idx, item -> "sug_item_${idx}_${item.kind}_${item.id}" },
                            contentType = { _, item -> item.kind }
                        ) { _, item ->
                            if (item.kind == BrowseKind.SONG) {
                                val song = remember(item) { item.toSongItem() }
                                TrackRow(
                                    song = song,
                                    isPlaying = nowPlayingId == song.videoId,
                                    onClick = { onOpenBrowseItem(item) },
                                    onMoreClick = { onTrackMore(song) }
                                )
                            } else {
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = MaterialTheme.colorScheme.onSurface,
                                        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(item.subtitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = {
                                        VelunaArtwork(
                                            url = item.thumbnail,
                                            contentDescription = item.title,
                                            isCircle = item.kind == BrowseKind.ARTIST,
                                            targetSizePx = 180,
                                            modifier = Modifier.size(44.dp)
                                        )
                                    },
                                    modifier = Modifier.clickable { onOpenBrowseItem(item) }
                                )
                            }
                        }
                    }
                }

                SearchContentMode.DISCOVER -> {
                    LazyColumn(
                        state = discoverListState,
                        flingBehavior = smoothFling,
                        contentPadding = PaddingValues(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (searchHistory.isNotEmpty()) {
                            item(key = "search_history_card", contentType = "search_history") {
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.search_recent),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        TextButton(onClick = onClearHistory) {
                                            Text(stringResource(R.string.search_clear_all), maxLines = 1, softWrap = false)
                                        }
                                    }
                                    searchHistory.take(6).forEach { hist ->
                                        ListItem(
                                            colors = ListItemDefaults.colors(
                                                containerColor = Color.Transparent,
                                                headlineColor = MaterialTheme.colorScheme.onSurface,
                                                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            headlineContent = { Text(hist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
                                            trailingContent = {
                                                IconButton(
                                                    onClick = { onRemoveHistory(hist) },
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_remove))
                                                }
                                            },
                                            modifier = Modifier
                                                .clickable { onSubmitSearch(hist) }
                                                .testTag("search_history_$hist")
                                        )
                                    }
                                }
                            }
                        }

                        if (state.isMoodsLoading && state.moodsSections.isEmpty()) {
                            item(key = "moods_loading", contentType = "loading") {
                                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }

                        state.moodsSections.forEachIndexed { secIdx, section ->
                            item(
                                key = "mood_hdr_${secIdx}_${section.title}",
                                contentType = "section_heading"
                            ) {
                                SectionHeading(
                                    title = section.title,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            val pairs = section.items.chunked(2)
                            items(
                                count = pairs.size,
                                key = { rowIdx -> "mood_row_${secIdx}_${section.title}_$rowIdx" },
                                contentType = { "mood_row" }
                            ) { rowIdx ->
                                val pair = pairs[rowIdx]
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    pair.forEach { tile ->
                                        val onTileClick = remember(tile, onOpenMoodTile) {
                                            { onOpenMoodTile(tile) }
                                        }
                                        MoodGridTile(
                                            tile = tile,
                                            sectionTitle = section.title,
                                            onClick = onTileClick,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
