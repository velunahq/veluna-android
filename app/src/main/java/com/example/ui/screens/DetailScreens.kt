package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.SongItem
import com.example.ui.components.ArtWashBackdrop
import com.example.ui.components.MediaSquareCard
import com.example.ui.components.SectionHeading
import com.example.ui.components.TrackRow
import com.example.ui.components.VelunaArtwork
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.rememberVelunaLazyListState
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors
import com.example.ui.theme.velunaPrimaryButtonColors
import com.example.ui.viewmodel.AlbumDetailState
import com.example.ui.viewmodel.ArtistDetailState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumDetailScreen(
    state: AlbumDetailState,
    isSaved: Boolean,
    nowPlayingId: String?,
    onBack: () -> Unit,
    onToggleSave: () -> Unit,
    onPinShortcut: (BrowseItem) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val phase = when {
        state.isLoading -> 0
        state.page == null -> 1
        else -> 2
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            fadeIn(VelunaMotion.tabTransitionTween()) togetherWith fadeOut(VelunaMotion.tabFadeOutTween())
        },
        modifier = modifier.fillMaxSize(),
        label = "album_detail_phase"
    ) { currentPhase ->
    when (currentPhase) {
        0 -> DetailLoading(onBack)
        1 -> DetailError(state.error ?: stringResource(R.string.detail_album_not_found), onBack)
        else -> {
            val album = state.page ?: return@AnimatedContent
            val listState = rememberVelunaLazyListState()
            val smoothFling = rememberVelunaFlingBehavior()
            Box(modifier = Modifier.fillMaxSize()) {
                ArtWashBackdrop(
                    artworkUrl = album.thumbnail,
                    accentColor = null,
                    modifier = Modifier.fillMaxWidth().height(340.dp)
                )
                LazyColumn(
                    state = listState,
                    flingBehavior = smoothFling,
                    contentPadding = PaddingValues(bottom = 28.dp),
                    modifier = Modifier.fillMaxSize().testTag("album_detail_screen")
                ) {
                    item(key = "album_topbar", contentType = "topbar") {
                        DetailTopBar(onBack = onBack)
                    }
                    item(key = "album_hero", contentType = "hero") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            VelunaArtwork(
                                url = album.thumbnail,
                                contentDescription = album.title,
                                shape = MaterialTheme.shapes.large,
                                targetSizePx = 600,
                                modifier = Modifier.size(204.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = album.title,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!album.artist.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = album.artist,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable(enabled = !album.artistId.isNullOrBlank()) {
                                        album.artistId?.let(onOpenArtist)
                                    }
                                )
                            }
                            if (!album.subtitle.isNullOrBlank() || !album.secondSubtitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = listOfNotNull(album.subtitle, album.secondSubtitle).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    enabled = album.items.isNotEmpty(),
                                    onClick = { onPlayTracks(album.items, 0, album.title) },
                                    colors = velunaPrimaryButtonColors(),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("album_play_btn")
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_play), maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(
                                    enabled = album.items.isNotEmpty(),
                                    onClick = { onPlayTracks(album.items.shuffled(), 0, album.title) },
                                    colors = velunaOutlinedButtonColors(),
                                    border = velunaOutlinedButtonBorder(album.items.isNotEmpty()),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("album_shuffle_btn")
                                ) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_shuffle), maxLines = 1, softWrap = false)
                                }
                                IconButton(
                                    onClick = onToggleSave,
                                    modifier = Modifier.size(48.dp).testTag("album_save_btn")
                                ) {
                                    Icon(
                                        imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                        contentDescription = stringResource(if (isSaved) R.string.detail_remove_album else R.string.detail_save_album),
                                        tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onPinShortcut(
                                            BrowseItem(BrowseKind.ALBUM, album.id, album.title, album.artist, album.thumbnail)
                                        )
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PushPin,
                                        contentDescription = stringResource(R.string.detail_pin_shortcut),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    itemsIndexed(
                        items = album.items,
                        key = { idx, s -> "al_${idx}_${s.videoId}" },
                        contentType = { _, _ -> "track" }
                    ) { idx, song ->
                        TrackRow(
                            song = song,
                            isPlaying = nowPlayingId == song.videoId,
                            trackNumber = idx + 1,
                            onClick = { onPlayTracks(album.items, idx, album.title) },
                            onMoreClick = { onTrackMore(song) }
                        )
                    }

                    itemsIndexed(
                        items = album.sections,
                        key = { secIdx, sec -> "al_sec_${secIdx}_${sec.title}" },
                        contentType = { _, _ -> "carousel" }
                    ) { secIdx, sec ->
                        Spacer(modifier = Modifier.height(14.dp))
                        SectionHeading(title = sec.title)
                        LazyRow(
                            flingBehavior = smoothFling,
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            itemsIndexed(
                                items = sec.items,
                                key = { itemIdx, item -> "al_${secIdx}_${itemIdx}_${item.id}" },
                                contentType = { _, _ -> "media_card" }
                            ) { _, item ->
                                MediaSquareCard(item = item, onClick = { onOpenBrowseItem(item) })
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistDetailScreen(
    state: ArtistDetailState,
    isFollowed: Boolean,
    nowPlayingId: String?,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit,
    onPinShortcut: (BrowseItem) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val phase = when {
        state.isLoading -> 0
        state.page == null -> 1
        else -> 2
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            fadeIn(VelunaMotion.tabTransitionTween()) togetherWith fadeOut(VelunaMotion.tabFadeOutTween())
        },
        modifier = modifier.fillMaxSize(),
        label = "artist_detail_phase"
    ) { currentPhase ->
    when (currentPhase) {
        0 -> DetailLoading(onBack)
        1 -> DetailError(state.error ?: stringResource(R.string.detail_artist_not_found), onBack)
        else -> {
            val artist = state.page ?: return@AnimatedContent
            val listState = rememberVelunaLazyListState()
            val smoothFling = rememberVelunaFlingBehavior()
            Box(modifier = Modifier.fillMaxSize()) {
                ArtWashBackdrop(
                    artworkUrl = artist.thumbnail ?: artist.avatar,
                    accentColor = null,
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                )
                LazyColumn(
                    state = listState,
                    flingBehavior = smoothFling,
                    contentPadding = PaddingValues(bottom = 28.dp),
                    modifier = Modifier.fillMaxSize().testTag("artist_detail_screen")
                ) {
                    item(key = "artist_topbar", contentType = "topbar") { DetailTopBar(onBack = onBack) }
                    item(key = "artist_hero", contentType = "hero") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            VelunaArtwork(
                                url = artist.avatar ?: artist.thumbnail,
                                contentDescription = artist.name,
                                isCircle = true,
                                targetSizePx = 600,
                                modifier = Modifier.size(164.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val statsLine = listOfNotNull(artist.subscribers, artist.monthlyListeners).joinToString(" • ")
                            if (statsLine.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = statsLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (artist.topSongs.isNotEmpty()) {
                                    Button(
                                        onClick = { onPlayTracks(artist.topSongs, 0, artist.name) },
                                        colors = velunaPrimaryButtonColors(),
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("artist_play_btn")
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.detail_play_top), maxLines = 1, softWrap = false)
                                    }
                                }
                                if (!artist.radioPlaylistId.isNullOrBlank()) {
                                    val radioTitle = stringResource(R.string.detail_radio_title, artist.name)
                                    OutlinedButton(
                                        onClick = {
                                            onOpenBrowseItem(BrowseItem(BrowseKind.PLAYLIST, artist.radioPlaylistId, radioTitle))
                                        },
                                        colors = velunaOutlinedButtonColors(),
                                        border = velunaOutlinedButtonBorder(),
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Icon(Icons.Rounded.Radio, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.detail_radio), maxLines = 1, softWrap = false)
                                    }
                                }
                                OutlinedButton(
                                    onClick = onToggleFollow,
                                    colors = velunaOutlinedButtonColors(),
                                    border = velunaOutlinedButtonBorder(),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("artist_follow_btn")
                                ) {
                                    Icon(
                                        imageVector = if (isFollowed) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(if (isFollowed) R.string.detail_following else R.string.detail_follow),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onPinShortcut(
                                            BrowseItem(BrowseKind.ARTIST, artist.channelId, artist.name, artist.subscribers, artist.avatar ?: artist.thumbnail)
                                        )
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PushPin,
                                        contentDescription = stringResource(R.string.detail_pin_shortcut),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    if (artist.topSongs.isNotEmpty()) {
                        item(key = "artist_top_hdr", contentType = "section_heading") {
                            SectionHeading(title = stringResource(R.string.detail_top_songs))
                        }
                        itemsIndexed(
                            items = artist.topSongs,
                            key = { idx, s -> "ar_${idx}_${s.videoId}" },
                            contentType = { _, _ -> "track" }
                        ) { idx, song ->
                            TrackRow(
                                song = song,
                                isPlaying = nowPlayingId == song.videoId,
                                onClick = { onPlayTracks(artist.topSongs, idx, artist.name) },
                                onMoreClick = { onTrackMore(song) }
                            )
                        }
                    }

                    itemsIndexed(
                        items = artist.sections,
                        key = { secIdx, sec -> "ar_sec_${secIdx}_${sec.title}" },
                        contentType = { _, _ -> "carousel" }
                    ) { secIdx, sec ->
                        Spacer(modifier = Modifier.height(14.dp))
                        SectionHeading(title = sec.title)
                        LazyRow(
                            flingBehavior = smoothFling,
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            itemsIndexed(
                                items = sec.items,
                                key = { itemIdx, item -> "ar_${secIdx}_${itemIdx}_${item.id}" },
                                contentType = { _, _ -> "media_card" }
                            ) { _, item ->
                                MediaSquareCard(item = item, onClick = { onOpenBrowseItem(item) })
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun DetailTopBar(
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag("detail_back_btn")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        actions()
    }
}

@Composable
internal fun DetailLoading(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
internal fun DetailError(msg: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack = onBack)
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}
