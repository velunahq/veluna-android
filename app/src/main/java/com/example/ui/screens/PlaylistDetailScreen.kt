package com.example.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.LOCAL_PLAYLIST_PREFIX
import com.example.model.SongItem
import com.example.ui.components.ArtWashBackdrop
import com.example.ui.components.CreateEditPlaylistDialog
import com.example.ui.components.TrackRow
import com.example.ui.components.VelunaArtwork
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.rememberVelunaLazyListState
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors
import com.example.ui.theme.velunaOutlinedFieldColors
import com.example.ui.theme.velunaPrimaryButtonColors
import com.example.ui.viewmodel.PlaylistDetailState
import com.example.ui.viewmodel.PlaylistSortMode
import com.example.util.SecurityValidators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailState,
    nowPlayingId: String?,
    onBack: () -> Unit,
    onFilterChange: (String) -> Unit,
    onSortChange: (PlaylistSortMode) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onEditLocalPlaylist: (Long, String, String, String?) -> Unit,
    onRemoveDuplicates: (Long) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onExportM3u: () -> Pair<String, String>?,
    onPinShortcut: (BrowseItem) -> Unit,
    onStatusMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    onExportCsv: () -> Pair<String, String>? = { null },
    onRemoveLocalTrack: (Long, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var pendingM3uContent by remember { mutableStateOf<String?>(null) }
    var pendingCsvContent by remember { mutableStateOf<String?>(null) }

    val exportedM3uMsg = stringResource(R.string.playlist_exported_m3u8)
    val exportedCsvMsg = stringResource(R.string.playlist_exported_csv)
    val pinnedMsg = stringResource(R.string.playlist_pinned_status)

    val exportM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        val content = pendingM3uContent
        if (uri != null && content != null) {
            scope.launch {
                try {
                    writeTextToUri(context, uri, content)
                    onStatusMessage(exportedM3uMsg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onStatusMessage(
                        context.getString(
                            R.string.playlist_export_failed,
                            SecurityValidators.safeUserErrorMessage(e, "Failed to write file")
                        )
                    )
                }
            }
        }
        pendingM3uContent = null
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val content = pendingCsvContent
        if (uri != null && content != null) {
            scope.launch {
                try {
                    writeTextToUri(context, uri, content)
                    onStatusMessage(exportedCsvMsg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onStatusMessage(
                        context.getString(
                            R.string.playlist_export_failed,
                            SecurityValidators.safeUserErrorMessage(e, "Failed to write file")
                        )
                    )
                }
            }
        }
        pendingCsvContent = null
    }

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
        label = "playlist_detail_phase"
    ) { currentPhase ->
    when (currentPhase) {
        0 -> DetailLoading(onBack)
        1 -> DetailError(state.error ?: stringResource(R.string.detail_playlist_not_found), onBack)
        else -> {
            val page = state.page ?: return@AnimatedContent
            val listState = rememberVelunaLazyListState()
            val smoothFling = rememberVelunaFlingBehavior()
            val numericLocalId = if (page.id.startsWith(LOCAL_PLAYLIST_PREFIX)) {
                page.id.removePrefix(LOCAL_PLAYLIST_PREFIX).toLongOrNull()
            } else null

            val displayedTracks = remember(page.items, state.filterQuery, state.sortMode) {
                val filtered = if (state.filterQuery.isBlank()) page.items else {
                    val q = state.filterQuery.trim()
                    page.items.filter {
                        it.title.contains(q, ignoreCase = true) || it.artists.contains(q, ignoreCase = true)
                    }
                }
                when (state.sortMode) {
                    PlaylistSortMode.CUSTOM -> filtered
                    PlaylistSortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
                    PlaylistSortMode.ARTIST -> filtered.sortedBy { it.artists.lowercase() }
                    PlaylistSortMode.RECENT -> filtered.sortedByDescending { it.addedAt ?: 0L }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                ArtWashBackdrop(
                    artworkUrl = page.thumbnail,
                    accentColor = null,
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                )
                LazyColumn(
                    state = listState,
                    flingBehavior = smoothFling,
                    contentPadding = PaddingValues(bottom = 28.dp),
                    modifier = Modifier.fillMaxSize().testTag("playlist_detail_screen")
                ) {
                    item(key = "pl_topbar", contentType = "topbar") {
                        DetailTopBar(
                            onBack = onBack,
                            actions = {
                                Box {
                                    IconButton(
                                        onClick = { menuExpanded = true },
                                        modifier = Modifier.size(48.dp).testTag("playlist_more_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = stringResource(R.string.playlist_menu),
                                            tint = MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.playlist_pin_shortcuts)) },
                                            leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                onPinShortcut(BrowseItem(BrowseKind.PLAYLIST, page.id, page.title, page.subtitle, page.thumbnail))
                                                onStatusMessage(pinnedMsg)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.playlist_export_m3u8)) },
                                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                val pair = onExportM3u()
                                                if (pair != null) {
                                                    pendingM3uContent = pair.second
                                                    exportM3uLauncher.launch(pair.first)
                                                }
                                            },
                                            modifier = Modifier.testTag("playlist_export_m3u_btn")
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.playlist_export_csv)) },
                                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                val pair = onExportCsv()
                                                if (pair != null) {
                                                    pendingCsvContent = pair.second
                                                    exportCsvLauncher.launch(pair.first)
                                                }
                                            },
                                            modifier = Modifier.testTag("playlist_export_csv_btn")
                                        )
                                        if (numericLocalId != null) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.playlist_edit_details)) },
                                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    showEditDialog = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.playlist_remove_dupes)) },
                                                leadingIcon = { Icon(Icons.Rounded.CleaningServices, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    onRemoveDuplicates(numericLocalId)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.playlist_delete)) },
                                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    showDeleteConfirm = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }

                    item(key = "pl_hero", contentType = "hero") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            VelunaArtwork(
                                url = page.thumbnail,
                                contentDescription = page.title,
                                specialId = page.id,
                                shape = MaterialTheme.shapes.large,
                                targetSizePx = 600,
                                modifier = Modifier.size(196.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = page.title,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!page.subtitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = page.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (!page.description.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = page.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    enabled = displayedTracks.isNotEmpty(),
                                    onClick = { onPlayTracks(displayedTracks, 0, page.title) },
                                    colors = velunaPrimaryButtonColors(),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_play_btn")
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_play), maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(
                                    enabled = displayedTracks.isNotEmpty(),
                                    onClick = { onPlayTracks(displayedTracks.shuffled(), 0, page.title) },
                                    colors = velunaOutlinedButtonColors(),
                                    border = velunaOutlinedButtonBorder(displayedTracks.isNotEmpty()),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_shuffle_btn")
                                ) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_shuffle), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.filterQuery,
                                onValueChange = onFilterChange,
                                placeholder = { Text(stringResource(R.string.playlist_filter_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                colors = velunaOutlinedFieldColors(),
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("playlist_filter_input")
                            )
                            Box {
                                OutlinedButton(
                                    onClick = { sortExpanded = true },
                                    colors = velunaOutlinedButtonColors(),
                                    border = velunaOutlinedButtonBorder(),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(state.sortMode.label, maxLines = 1, softWrap = false)
                                }
                                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                    PlaylistSortMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            onClick = {
                                                sortExpanded = false
                                                onSortChange(mode)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (displayedTracks.isEmpty()) {
                        item(key = "pl_empty", contentType = "empty") {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.playlist_empty_tracks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = displayedTracks,
                            key = { idx, s -> "pl_${idx}_${s.videoId}" },
                            contentType = { _, _ -> "track" }
                        ) { idx, song ->
                            if (numericLocalId != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        TrackRow(
                                            song = song,
                                            isPlaying = nowPlayingId == song.videoId,
                                            onClick = { onPlayTracks(displayedTracks, idx, page.title) },
                                            onMoreClick = { onTrackMore(song) }
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveLocalTrack(numericLocalId, song.videoId) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("playlist_remove_track_${song.videoId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = stringResource(R.string.playlist_remove_track),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                TrackRow(
                                    song = song,
                                    isPlaying = nowPlayingId == song.videoId,
                                    onClick = { onPlayTracks(displayedTracks, idx, page.title) },
                                    onMoreClick = { onTrackMore(song) }
                                )
                            }
                        }
                    }
                }
            }

            if (showEditDialog && numericLocalId != null) {
                CreateEditPlaylistDialog(
                    initialTitle = page.title,
                    initialDescription = page.description.orEmpty(),
                    initialCoverUri = page.coverUri,
                    isEditing = true,
                    onDismiss = { showEditDialog = false },
                    onConfirm = { t, d, cover ->
                        onEditLocalPlaylist(numericLocalId, t, d, cover)
                    }
                )
            }

            if (showDeleteConfirm && numericLocalId != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text(stringResource(R.string.playlist_delete_title, page.title)) },
                    text = { Text(stringResource(R.string.playlist_delete_body)) },
                    confirmButton = {
                        Button(onClick = {
                            showDeleteConfirm = false
                            onDeletePlaylist(numericLocalId)
                        }) { Text(stringResource(R.string.action_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }
        }
    }
    }
}

private suspend fun writeTextToUri(context: Context, uri: Uri, content: String) {
    withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the selected file")
        output.bufferedWriter().use { it.write(content) }
    }
}
