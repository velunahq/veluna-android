package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.TasteEngine
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.ui.components.ArtWashBackdrop
import com.example.ui.components.VelunaArtwork
import com.example.ui.theme.velunaFilterChipBorder
import com.example.ui.theme.velunaFilterChipColors
import com.example.ui.theme.velunaInputChipColors
import com.example.ui.theme.velunaOutlinedFieldColors
import com.example.ui.viewmodel.OnboardingSearchUiState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasteOnboardingScreen(
    initialProfile: TasteProfile?,
    searchState: OnboardingSearchUiState,
    canNavigateBack: Boolean,
    onQueryChange: (String) -> Unit,
    onSave: (genres: List<String>, artists: List<TasteSeed>, songs: List<TasteSeed>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (canNavigateBack) {
        BackHandler { onSkip() }
    }

    val seedListSaver = remember {
        listSaver<SnapshotStateList<TasteSeed>, String>(
            save = { list -> list.map { seed -> Json.encodeToString(seed) } },
            restore = { saved ->
                mutableStateListOf<TasteSeed>().apply {
                    saved.mapNotNull { raw -> runCatching { Json.decodeFromString<TasteSeed>(raw) }.getOrNull() }
                        .let { addAll(it) }
                }
            }
        )
    }
    val stringListSaver = remember {
        listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { saved -> mutableStateListOf<String>().apply { addAll(saved) } }
        )
    }

    val selectedGenres = rememberSaveable(saver = stringListSaver) {
        mutableStateListOf<String>()
    }
    val selectedArtists = rememberSaveable(saver = seedListSaver) {
        mutableStateListOf<TasteSeed>()
    }
    val selectedSongs = rememberSaveable(saver = seedListSaver) {
        mutableStateListOf<TasteSeed>()
    }
    var hasHydrated by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialProfile) {
        if (!hasHydrated && initialProfile != null) {
            selectedGenres.clear()
            selectedGenres.addAll(initialProfile.selectedGenres)
            selectedArtists.clear()
            selectedArtists.addAll(initialProfile.seedArtists)
            selectedSongs.clear()
            selectedSongs.addAll(initialProfile.seedSongs)
            hasHydrated = true
        }
    }

    val totalSelected = selectedGenres.size + selectedArtists.size + selectedSongs.size

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .fillMaxSize()
            .testTag("taste_onboarding_screen")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ArtWashBackdrop(
                artworkUrl = selectedSongs.lastOrNull()?.thumbnail ?: selectedArtists.lastOrNull()?.thumbnail,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OnboardingHeader(canNavigateBack = canNavigateBack, onSkip = onSkip)

                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                ) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_search_heading),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                OutlinedTextField(
                                    value = searchState.query,
                                    onValueChange = onQueryChange,
                                    placeholder = { Text(stringResource(R.string.onboarding_search_placeholder)) },
                                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                    trailingIcon = {
                                        when {
                                            searchState.isSearching -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                            searchState.query.isNotEmpty() -> {
                                                IconButton(
                                                    onClick = { onQueryChange("") },
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.onboarding_clear_search))
                                                }
                                            }
                                        }
                                    },
                                    colors = velunaOutlinedFieldColors(),
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_search_input")
                                )
                            }
                        }
                    }

                    if (selectedArtists.isNotEmpty() || selectedSongs.isNotEmpty()) {
                        item {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_your_picks, selectedArtists.size + selectedSongs.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val chipColors = velunaInputChipColors()
                                    val removeDesc = stringResource(R.string.action_remove)
                                    selectedArtists.forEach { artist ->
                                        InputChip(
                                            selected = true,
                                            onClick = {
                                                hasHydrated = true
                                                selectedArtists.removeAll { it.id == artist.id }
                                            },
                                            colors = chipColors,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                            label = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = removeDesc, modifier = Modifier.size(16.dp)) }
                                        )
                                    }
                                    selectedSongs.forEach { song ->
                                        InputChip(
                                            selected = true,
                                            onClick = {
                                                hasHydrated = true
                                                selectedSongs.removeAll { it.id == song.id }
                                            },
                                            colors = chipColors,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                            label = { Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = removeDesc, modifier = Modifier.size(16.dp)) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (searchState.artists.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = stringResource(R.string.onboarding_matching_artists),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(searchState.artists, key = { "onb_art_${it.id}" }) { artist ->
                                        val picked = selectedArtists.any { it.id == artist.id }
                                        val borderCol = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(96.dp)
                                                .clip(MaterialTheme.shapes.medium)
                                                .clickable {
                                                    hasHydrated = true
                                                    if (picked) selectedArtists.removeAll { it.id == artist.id }
                                                    else selectedArtists.add(TasteSeed(artist.id, artist.title, artist.subtitle, artist.thumbnail, updatedAtMs = System.currentTimeMillis()))
                                                }
                                                .padding(4.dp)
                                                .testTag("onboarding_artist_${artist.id}")
                                        ) {
                                            Box(modifier = Modifier.size(76.dp)) {
                                                VelunaArtwork(
                                                    url = artist.thumbnail,
                                                    contentDescription = artist.title,
                                                    isCircle = true,
                                                    targetSizePx = 240,
                                                    modifier = Modifier.matchParentSize().border(if (picked) 3.dp else 1.dp, borderCol, CircleShape)
                                                )
                                                if (picked) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CheckCircle,
                                                        contentDescription = stringResource(R.string.onboarding_selected),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.align(Alignment.BottomEnd).size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = artist.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (searchState.songs.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.onboarding_matching_songs),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(searchState.songs, key = { "onb_song_${it.id}" }) { song ->
                            val picked = selectedSongs.any { it.id == song.id }
                            val containerColor = if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            val titleColor = if (picked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            val subtitleColor = if (picked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                            Surface(
                                color = containerColor,
                                contentColor = titleColor,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        hasHydrated = true
                                        if (picked) selectedSongs.removeAll { it.id == song.id }
                                        else selectedSongs.add(TasteSeed(song.id, song.title, song.subtitle, song.thumbnail, updatedAtMs = System.currentTimeMillis()))
                                    }
                                    .testTag("onboarding_song_${song.id}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    VelunaArtwork(
                                        url = song.thumbnail,
                                        contentDescription = song.title,
                                        targetSizePx = 180,
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(song.title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!song.subtitle.isNullOrBlank()) {
                                            Text(song.subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Icon(
                                        imageVector = if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.Add,
                                        contentDescription = stringResource(if (picked) R.string.onboarding_selected else R.string.onboarding_select_song),
                                        tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_pick_genres_title),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_pick_genres_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val filterChipColors = velunaFilterChipColors()
                                    TasteEngine.AVAILABLE_GENRES.forEach { genre ->
                                        val selected = genre.id in selectedGenres
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                hasHydrated = true
                                                if (selected) selectedGenres.remove(genre.id) else selectedGenres.add(genre.id)
                                            },
                                            colors = filterChipColors,
                                            border = velunaFilterChipBorder(selected),
                                            label = { Text(genre.label, maxLines = 1, softWrap = false) },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding_genre_${genre.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                OnboardingBottomCtaBar(
                    totalSelected = totalSelected,
                    onSaveClick = {
                        onSave(selectedGenres.toList(), selectedArtists.toList(), selectedSongs.toList())
                    }
                )
            }
        }
    }
}
