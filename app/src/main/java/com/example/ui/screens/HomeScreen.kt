package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.cloud.CloudAuthUiState
import com.example.data.local.VelunaUserPrefs
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.SongItem
import com.example.ui.components.ArtWashBackdrop
import com.example.ui.components.FamiliarArtistCard
import com.example.ui.components.MediaSquareCard
import com.example.ui.components.SectionHeading
import com.example.ui.components.ShortcutTile
import com.example.ui.components.TrackRow
import com.example.ui.components.VelunaArtwork
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.rememberVelunaLazyListState
import com.example.ui.theme.velunaFilterChipBorder
import com.example.ui.theme.velunaFilterChipColors
import com.example.ui.viewmodel.HomeUiState
import java.time.LocalTime

@Composable
fun HomeScreen(
    homeState: HomeUiState,
    prefs: VelunaUserPrefs,
    shortcuts: List<BrowseItem>,
    jumpBack: List<BrowseItem>,
    familiarArtists: List<BrowseItem>,
    nowPlayingSong: SongItem?,
    artworkAccent: Color?,
    modifier: Modifier = Modifier,
    likedIds: Set<String> = emptySet(),
    cloudAuthState: CloudAuthUiState = CloudAuthUiState(),
    onSignInWithGoogle: () -> Unit = {},
    onOpenAccountSettings: () -> Unit = {},
    onSelectChip: (String?) -> Unit,
    onRetry: () -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onToggleLike: ((SongItem) -> Unit)? = null,
    onOpenHistory: () -> Unit,
    onOpenEditHome: () -> Unit
) {
    val currentHour = remember { LocalTime.now().hour }
    val prefix = when (currentHour) {
        in 5..11 -> stringResource(R.string.home_good_morning)
        in 12..16 -> stringResource(R.string.home_good_afternoon)
        in 17..21 -> stringResource(R.string.home_good_evening)
        else -> stringResource(R.string.home_good_night)
    }
    val greeting = remember(prefs.userDisplayName, prefix) {
        val name = prefs.userDisplayName.takeIf { it.isNotBlank() && it != "Listener" }
        if (name != null) "$prefix, $name" else prefix
    }
    val shortcutPairs = remember(shortcuts) { shortcuts.take(6).chunked(2) }

    val listState = rememberVelunaLazyListState()
    val smoothFling = rememberVelunaFlingBehavior()

    Box(modifier = modifier.fillMaxSize()) {
        if (prefs.showHeroArtworkBackdrop) {
            ArtWashBackdrop(
                artworkUrl = nowPlayingSong?.thumbnail,
                accentColor = artworkAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            )
        }

        LazyColumn(
            state = listState,
            flingBehavior = smoothFling,
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen_list")
        ) {
            // Top Greeting & Action Bar
            item(key = "home_header", contentType = "home_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_veluna_mark_art),
                            contentDescription = stringResource(R.string.app_name),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = homeState.moodsTitle ?: greeting,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    if (homeState.moodsTitle != null) R.string.home_subtitle_mood else R.string.home_subtitle_default
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onOpenHistory,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_history_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = stringResource(R.string.home_listening_history),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = onOpenEditHome,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_customize_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.home_customize_home),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = {
                                if (cloudAuthState.isSignedIn) onOpenAccountSettings()
                                else onSignInWithGoogle()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_account_btn")
                        ) {
                            if (cloudAuthState.isSignedIn && !cloudAuthState.photoUrl.isNullOrBlank()) {
                                VelunaArtwork(
                                    url = cloudAuthState.photoUrl,
                                    contentDescription = cloudAuthState.displayName ?: stringResource(R.string.home_account_settings),
                                    isCircle = true,
                                    targetSizePx = 120,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = stringResource(
                                        if (cloudAuthState.isSignedIn) R.string.home_account_settings else R.string.home_google_signin
                                    ),
                                    tint = if (cloudAuthState.isSignedIn) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onBackground
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Mood Filter Chips
            if (prefs.showMoodChips && homeState.homePage.chips.isNotEmpty()) {
                item(key = "home_mood_chips", contentType = "chips_row") {
                    val chipColors = velunaFilterChipColors()
                    val allSelected = homeState.selectedChipParams == null && homeState.moodsTitle == null
                    LazyRow(
                        flingBehavior = smoothFling,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "chip_all", contentType = "chip") {
                            FilterChip(
                                selected = allSelected,
                                onClick = { onSelectChip(null) },
                                colors = chipColors,
                                border = velunaFilterChipBorder(allSelected),
                                label = { Text(stringResource(R.string.home_chip_all), maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("home_chip_all")
                            )
                        }
                        itemsIndexed(
                            items = homeState.homePage.chips,
                            key = { idx, chip -> "chip_${idx}_${chip.title}_${chip.params}" },
                            contentType = { _, _ -> "chip" }
                        ) { _, chip ->
                            val chipSelected = homeState.selectedChipParams == chip.params && chip.params.isNotBlank()
                            FilterChip(
                                selected = chipSelected,
                                onClick = {
                                    if (chip.params.isNotBlank()) onSelectChip(chip.params)
                                    else onSelectChip(null)
                                },
                                colors = chipColors,
                                border = velunaFilterChipBorder(chipSelected),
                                label = { Text(chip.title, maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("home_chip_${chip.title.lowercase().replace(' ', '_')}")
                            )
                        }
                    }
                }
            }

            // Shortcuts Grid
            if (shortcutPairs.isNotEmpty()) {
                item(key = "home_shortcuts_grid", contentType = "shortcuts") {
                    Column {
                        SectionHeading(title = stringResource(R.string.home_shortcuts))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            shortcutPairs.forEach { pair ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    pair.forEach { sc ->
                                        ShortcutTile(
                                            item = sc,
                                            onClick = { onOpenBrowseItem(sc) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Jump Back In
            if (jumpBack.isNotEmpty() && homeState.moodsTitle == null && homeState.selectedChipParams == null) {
                item(key = "home_jump_back", contentType = "carousel_shelf") {
                    Column {
                        SectionHeading(
                            title = stringResource(R.string.home_jump_back_in),
                            actionLabel = stringResource(R.string.home_history_action),
                            onActionClick = onOpenHistory
                        )
                        LazyRow(
                            flingBehavior = smoothFling,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                items = jumpBack,
                                key = { idx, item -> "jump_${idx}_${item.id}" },
                                contentType = { _, _ -> "media_card" }
                            ) { _, item ->
                                val onClick = remember(item, onOpenBrowseItem) { { onOpenBrowseItem(item) } }
                                MediaSquareCard(
                                    item = item,
                                    showPlayBadge = true,
                                    onClick = onClick
                                )
                            }
                        }
                    }
                }
            }

            // Familiar Artists (01, 02, ...)
            if (familiarArtists.isNotEmpty() && homeState.moodsTitle == null && homeState.selectedChipParams == null) {
                item(key = "home_familiar_artists", contentType = "artists_shelf") {
                    Column {
                        SectionHeading(title = stringResource(R.string.home_familiar_artists))
                        LazyRow(
                            flingBehavior = smoothFling,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                items = familiarArtists,
                                key = { idx, a -> "fam_${idx}_${a.id}" },
                                contentType = { _, _ -> "familiar_artist" }
                            ) { idx, artist ->
                                val onClick = remember(artist, onOpenBrowseItem) { { onOpenBrowseItem(artist) } }
                                FamiliarArtistCard(
                                    index = idx,
                                    item = artist,
                                    onClick = onClick
                                )
                            }
                        }
                    }
                }
            }

            // Loading / Error state for remote InnerTube home feed
            if (homeState.isLoading) {
                item(key = "home_loading", contentType = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (!homeState.error.isNullOrBlank() && homeState.homePage.sections.isEmpty()) {
                item(key = "home_error", contentType = "error") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.home_feed_error_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = homeState.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.testTag("home_retry_btn")
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_retry), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // Remote InnerTube & Personalized Home Shelves
            homeState.homePage.sections.forEachIndexed { secIdx, section ->
                val isQuickPicks = section.kind == "PERSONALIZED_SONGS" ||
                    section.title.contains("quick picks", ignoreCase = true) ||
                    (section.items.isNotEmpty() && section.items.all { it.kind == BrowseKind.SONG } && section.items.size <= 16)

                if (isQuickPicks) {
                    val quickPicks = section.items.take(8)
                    item(
                        key = "sec_hdr_${secIdx}_${section.kind}_${section.title}",
                        contentType = "section_heading"
                    ) {
                        val openLabel = stringResource(R.string.home_open_action)
                        SectionHeading(
                            title = section.title,
                            actionLabel = if (!section.moreBrowseId.isNullOrBlank()) openLabel else null,
                            onActionClick = section.moreBrowseId?.let { bid ->
                                {
                                    onOpenBrowseItem(
                                        BrowseItem(
                                            kind = BrowseKind.PLAYLIST,
                                            id = bid,
                                            title = section.title
                                        )
                                    )
                                }
                            }
                        )
                    }
                    itemsIndexed(
                        items = quickPicks,
                        key = { idx, item -> "qp_${secIdx}_${idx}_${item.id}" },
                        contentType = { _, _ -> "quick_pick_song" }
                    ) { _, item ->
                        val song = remember(item) { item.toSongItem() }
                        val onClick = remember(item, onOpenBrowseItem) { { onOpenBrowseItem(item) } }
                        val onMoreClick = remember(song, onTrackMore) { { onTrackMore(song) } }
                        val onLikeClick = remember(song, onToggleLike) {
                            onToggleLike?.let { fn -> { fn(song) } }
                        }
                        TrackRow(
                            song = song,
                            isPlaying = nowPlayingSong?.videoId == song.videoId,
                            isLiked = song.videoId in likedIds,
                            onClick = onClick,
                            onMoreClick = onMoreClick,
                            onLikeClick = onLikeClick,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                } else {
                    item(
                        key = "${secIdx}_${section.kind}_${section.title}_${section.items.size}",
                        contentType = "carousel_shelf"
                    ) {
                        Column {
                            val openLabel = stringResource(R.string.home_open_action)
                            SectionHeading(
                                title = section.title,
                                actionLabel = if (!section.moreBrowseId.isNullOrBlank()) openLabel else null,
                                onActionClick = section.moreBrowseId?.let { bid ->
                                    {
                                        onOpenBrowseItem(
                                            BrowseItem(
                                                kind = BrowseKind.PLAYLIST,
                                                id = bid,
                                                title = section.title
                                            )
                                        )
                                    }
                                }
                            )
                            LazyRow(
                                flingBehavior = smoothFling,
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(
                                    items = section.items,
                                    key = { itemIdx, item -> "${secIdx}_${itemIdx}_${item.kind}_${item.id}" },
                                    contentType = { _, _ -> "media_card" }
                                ) { _, item ->
                                    val onClick = remember(item, onOpenBrowseItem) { { onOpenBrowseItem(item) } }
                                    MediaSquareCard(
                                        item = item,
                                        showPlayBadge = item.kind == BrowseKind.SONG,
                                        onClick = onClick
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
