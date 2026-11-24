package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrowseItem
import com.example.model.DownloadProgress
import com.example.model.SongItem
import com.example.ui.components.TrackRow
import com.example.ui.components.VelunaArtwork
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.rememberVelunaLazyListState
import com.example.ui.theme.velunaFilterChipBorder
import com.example.ui.theme.velunaFilterChipColors
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors
import com.example.ui.theme.velunaPrimaryButtonColors

@Composable
fun LibraryScreen(
    playlists: List<BrowseItem>,
    likedSongs: List<SongItem>,
    savedAlbums: List<BrowseItem>,
    followedArtists: List<BrowseItem>,
    downloads: List<SongItem>,
    activeDownloads: Map<String, DownloadProgress>,
    localTracks: List<SongItem>,
    nowPlayingId: String?,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onPlaySongs: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onToggleLike: (SongItem) -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onOpenImportPlaylist: () -> Unit,
    onImportLocalFiles: (List<Uri>) -> Unit,
    onImportLocalFolder: (Uri) -> Unit,
    onRemoveLocalTrack: (String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetryDownload: (DownloadProgress) -> Unit = {},
    onDismissDownloadStatus: (String) -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val smoothFling = rememberVelunaFlingBehavior()
    val playlistsListState = rememberVelunaLazyListState()
    val likedListState = rememberVelunaLazyListState()
    val albumsListState = rememberVelunaLazyListState()
    val artistsListState = rememberVelunaLazyListState()
    val tabs = listOf(
        "playlists" to stringResource(R.string.library_tab_playlists),
        "liked_songs" to stringResource(R.string.library_tab_liked_songs),
        "albums" to stringResource(R.string.library_tab_albums),
        "artists" to stringResource(R.string.library_tab_artists),
        "downloads" to stringResource(R.string.library_tab_downloads),
        "local_music" to stringResource(R.string.library_tab_local_music)
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onOpenImportPlaylist,
                    colors = velunaOutlinedButtonColors(),
                    border = velunaOutlinedButtonBorder(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("library_import_btn")
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.library_import), maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onOpenCreatePlaylist,
                    colors = velunaPrimaryButtonColors(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("library_new_playlist_btn")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.library_new), maxLines = 1, softWrap = false)
                }
            }

            val chipColors = velunaFilterChipColors()
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { idx, (key, label) ->
                    val selected = selectedTab == idx
                    FilterChip(
                        selected = selected,
                        onClick = { selectedTab = idx },
                        colors = chipColors,
                        border = velunaFilterChipBorder(selected),
                        label = { Text(label, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("library_tab_$key")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

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
                modifier = Modifier.weight(1f),
                label = "library_tab_content"
            ) { tab ->
                when (tab) {
                    0 -> {
                        LazyColumn(
                            state = playlistsListState,
                            flingBehavior = smoothFling,
                            contentPadding = PaddingValues(bottom = 28.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(
                                items = playlists,
                                key = { idx, pl -> "pl_${idx}_${pl.id}" },
                                contentType = { _, _ -> "playlist_row" }
                            ) { _, pl ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = MaterialTheme.colorScheme.onSurface,
                                        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    headlineContent = { Text(pl.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(pl.subtitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = {
                                        VelunaArtwork(
                                            url = pl.thumbnail,
                                            contentDescription = pl.title,
                                            specialId = pl.id,
                                            targetSizePx = 180,
                                            modifier = Modifier.size(54.dp)
                                        )
                                    },
                                    modifier = Modifier
                                        .clickable { onOpenBrowseItem(pl) }
                                        .testTag("library_playlist_${pl.id}")
                                )
                            }
                        }
                    }

                    1 -> {
                        if (likedSongs.isEmpty()) {
                            EmptyLibraryNotice(
                                title = stringResource(R.string.library_empty_liked_title),
                                body = stringResource(R.string.library_empty_liked_body)
                            )
                        } else {
                            val likedTitle = stringResource(R.string.library_tab_liked_songs)
                            LazyColumn(
                                state = likedListState,
                                flingBehavior = smoothFling,
                                contentPadding = PaddingValues(bottom = 28.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "liked_header", contentType = "header") {
                                    PlayShuffleHeader(
                                        countLabel = stringResource(R.string.library_liked_count, likedSongs.size),
                                        onPlayAll = { onPlaySongs(likedSongs, 0, likedTitle) },
                                        onShuffleAll = { onPlaySongs(likedSongs.shuffled(), 0, likedTitle) }
                                    )
                                }
                                itemsIndexed(
                                    items = likedSongs,
                                    key = { idx, song -> "liked_${idx}_${song.videoId}" },
                                    contentType = { _, _ -> "track" }
                                ) { idx, song ->
                                    TrackRow(
                                        song = song,
                                        isPlaying = nowPlayingId == song.videoId,
                                        onClick = { onPlaySongs(likedSongs, idx, likedTitle) },
                                        onMoreClick = { onTrackMore(song) },
                                        onLikeClick = { onToggleLike(song) }
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        if (savedAlbums.isEmpty()) {
                            EmptyLibraryNotice(
                                title = stringResource(R.string.library_empty_albums_title),
                                body = stringResource(R.string.library_empty_albums_body)
                            )
                        } else {
                            LazyColumn(
                                state = albumsListState,
                                flingBehavior = smoothFling,
                                contentPadding = PaddingValues(bottom = 28.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(
                                    items = savedAlbums,
                                    key = { idx, al -> "al_${idx}_${al.id}" },
                                    contentType = { _, _ -> "album_row" }
                                ) { _, al ->
                                    ListItem(
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent,
                                            headlineColor = MaterialTheme.colorScheme.onSurface,
                                            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        headlineContent = { Text(al.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(al.subtitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = {
                                            VelunaArtwork(
                                                url = al.thumbnail,
                                                contentDescription = al.title,
                                                targetSizePx = 180,
                                                modifier = Modifier.size(54.dp)
                                            )
                                        },
                                        modifier = Modifier
                                            .clickable { onOpenBrowseItem(al) }
                                            .testTag("library_album_${al.id}")
                                    )
                                }
                            }
                        }
                    }

                    3 -> {
                        if (followedArtists.isEmpty()) {
                            EmptyLibraryNotice(
                                title = stringResource(R.string.library_empty_artists_title),
                                body = stringResource(R.string.library_empty_artists_body)
                            )
                        } else {
                            LazyColumn(
                                state = artistsListState,
                                flingBehavior = smoothFling,
                                contentPadding = PaddingValues(bottom = 28.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(
                                    items = followedArtists,
                                    key = { idx, ar -> "ar_${idx}_${ar.id}" },
                                    contentType = { _, _ -> "artist_row" }
                                ) { _, ar ->
                                    ListItem(
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent,
                                            headlineColor = MaterialTheme.colorScheme.onSurface,
                                            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        headlineContent = { Text(ar.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(ar.subtitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = {
                                            VelunaArtwork(
                                                url = ar.thumbnail,
                                                contentDescription = ar.title,
                                                isCircle = true,
                                                targetSizePx = 180,
                                                modifier = Modifier.size(52.dp)
                                            )
                                        },
                                        modifier = Modifier
                                            .clickable { onOpenBrowseItem(ar) }
                                            .testTag("library_artist_${ar.id}")
                                    )
                                }
                            }
                        }
                    }

                    4 -> LibraryDownloadsTab(
                        downloads = downloads,
                        activeDownloads = activeDownloads,
                        nowPlayingId = nowPlayingId,
                        onPlaySongs = onPlaySongs,
                        onTrackMore = onTrackMore,
                        onDeleteDownload = onDeleteDownload,
                        onRetryDownload = onRetryDownload,
                        onDismissDownloadStatus = onDismissDownloadStatus
                    )

                    else -> LibraryLocalMusicTab(
                        localTracks = localTracks,
                        nowPlayingId = nowPlayingId,
                        onPlaySongs = onPlaySongs,
                        onTrackMore = onTrackMore,
                        onImportLocalFiles = onImportLocalFiles,
                        onImportLocalFolder = onImportLocalFolder,
                        onRemoveLocalTrack = onRemoveLocalTrack
                    )
                }
            }
        }
    }
}
