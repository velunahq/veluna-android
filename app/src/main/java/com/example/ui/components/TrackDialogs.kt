package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrowseItem
import com.example.model.Rating
import com.example.model.SongItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuSheet(
    song: SongItem,
    isDownloaded: Boolean,
    isLiked: Boolean = song.rating == Rating.LIKE,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleDownload: () -> Unit,
    onGoToArtist: ((String) -> Unit)?,
    onGoToAlbum: ((String) -> Unit)?,
    onPinShortcut: () -> Unit,
    onBlockArtist: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("track_menu_sheet")
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VelunaArtwork(
                    url = song.thumbnail,
                    contentDescription = song.title,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(song.artists, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            MenuActionRow(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.track_menu_play_next), "menu_play_next") {
                onPlayNext(); onDismiss()
            }
            MenuActionRow(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.track_menu_add_queue), "menu_add_queue") {
                onAddToQueue(); onDismiss()
            }
            if (!song.isLocal) {
                MenuActionRow(Icons.Rounded.Radio, stringResource(R.string.track_menu_start_radio), "menu_start_radio") {
                    onStartRadio(); onDismiss()
                }
                MenuActionRow(
                    icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    label = stringResource(if (isLiked) R.string.track_menu_remove_liked else R.string.track_menu_add_liked),
                    tag = "menu_toggle_like",
                    iconTint = if (isLiked) Color(0xFFEF4444) else LocalContentColor.current
                ) {
                    onToggleLike(); onDismiss()
                }
                MenuActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.track_menu_add_playlist), "menu_add_playlist") {
                    onDismiss(); onAddToPlaylist()
                }
                MenuActionRow(
                    icon = if (isDownloaded) Icons.Rounded.Delete else Icons.Rounded.Download,
                    label = stringResource(if (isDownloaded) R.string.track_menu_remove_download else R.string.track_menu_download),
                    tag = "menu_toggle_download"
                ) {
                    onToggleDownload(); onDismiss()
                }
            }
            val artistId = song.artistId ?: song.artistRuns.firstOrNull { it.id != null }?.id
            if (artistId != null && onGoToArtist != null) {
                MenuActionRow(Icons.Rounded.Person, stringResource(R.string.track_menu_go_artist), "menu_go_artist") {
                    onDismiss(); onGoToArtist(artistId)
                }
            }
            if (!song.albumId.isNullOrBlank() && onGoToAlbum != null) {
                MenuActionRow(Icons.Rounded.Album, stringResource(R.string.track_menu_go_album), "menu_go_album") {
                    onDismiss(); onGoToAlbum(song.albumId)
                }
            }
            MenuActionRow(Icons.Rounded.PushPin, stringResource(R.string.track_menu_pin_shortcut), "menu_pin_shortcut") {
                onPinShortcut(); onDismiss()
            }
            if (song.artists.isNotBlank()) {
                val primaryArtist = song.artists.substringBefore(',')
                MenuActionRow(
                    icon = Icons.Rounded.Block,
                    label = stringResource(R.string.track_menu_hide_artist, primaryArtist),
                    tag = "menu_block_artist"
                ) {
                    onBlockArtist(); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    label: String,
    tag: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface
        ),
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null, tint = iconTint) },
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(tag)
    )
}

@Composable
fun SleepTimerDialog(
    activeRemainingMs: Long?,
    onDismiss: () -> Unit,
    onSelectMinutes: (Int?) -> Unit
) {
    val presets = listOf(10, 15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(stringResource(R.string.sleep_timer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeRemainingMs != null) {
                    val mins = (activeRemainingMs / 60_000L) + 1
                    Text(
                        text = stringResource(R.string.sleep_timer_active, mins.toInt()),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                presets.forEach { m ->
                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = MaterialTheme.colorScheme.onSurface
                        ),
                        headlineContent = { Text(stringResource(R.string.sleep_timer_minutes, m)) },
                        modifier = Modifier
                            .clickable {
                                onSelectMinutes(m)
                                onDismiss()
                            }
                            .testTag("sleep_preset_$m")
                    )
                }
            }
        },
        confirmButton = {
            if (activeRemainingMs != null) {
                TextButton(onClick = {
                    onSelectMinutes(null)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.sleep_timer_turn_off))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
fun EditHomeDialog(
    showMoodChips: Boolean,
    showHeroBackdrop: Boolean,
    shortcuts: List<BrowseItem>,
    onToggleMoodChips: (Boolean) -> Unit,
    onToggleHeroBackdrop: (Boolean) -> Unit,
    onRemoveShortcut: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(stringResource(R.string.edit_home_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.edit_home_mood_chips), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = showMoodChips,
                        onCheckedChange = onToggleMoodChips,
                        modifier = Modifier.testTag("home_toggle_mood_chips")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.edit_home_artwork_backdrop), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = showHeroBackdrop,
                        onCheckedChange = onToggleHeroBackdrop,
                        modifier = Modifier.testTag("home_toggle_hero_backdrop")
                    )
                }
                if (shortcuts.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(stringResource(R.string.edit_home_pinned_shortcuts), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    LazyColumn(modifier = Modifier.height(160.dp)) {
                        items(shortcuts, key = { it.id }) { sc ->
                            ListItem(
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                    headlineColor = MaterialTheme.colorScheme.onSurface
                                ),
                                headlineContent = { Text(sc.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = {
                                    TextButton(onClick = { onRemoveShortcut(sc.id) }) {
                                        Text(stringResource(R.string.action_remove))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )
}
