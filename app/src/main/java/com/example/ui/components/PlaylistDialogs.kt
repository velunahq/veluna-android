package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrowseItem
import com.example.model.LOCAL_PLAYLIST_PREFIX
import com.example.model.SongItem
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors
import com.example.ui.theme.velunaOutlinedFieldColors
import com.example.ui.theme.velunaPrimaryButtonColors
import com.example.ui.viewmodel.ImportDialogState

@Composable
fun AddToPlaylistDialog(
    song: SongItem,
    playlists: List<BrowseItem>,
    onDismiss: () -> Unit,
    onPickPlaylist: (Long) -> Unit,
    onCreateNew: (String) -> Unit
) {
    var newTitle by remember { mutableStateOf("") }
    val localPlaylists = playlists.filter { it.id.startsWith(LOCAL_PLAYLIST_PREFIX) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(stringResource(R.string.playlist_dialog_add_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text(stringResource(R.string.playlist_dialog_new_name)) },
                        colors = velunaOutlinedFieldColors(),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_new_playlist_input")
                    )
                    Button(
                        enabled = newTitle.isNotBlank(),
                        colors = velunaPrimaryButtonColors(),
                        onClick = {
                            onCreateNew(newTitle.trim())
                            onDismiss()
                        },
                        modifier = Modifier.testTag("quick_new_playlist_create")
                    ) {
                        Text(stringResource(R.string.action_create))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(localPlaylists, key = { it.id }) { pl ->
                        val numericId = pl.id.removePrefix(LOCAL_PLAYLIST_PREFIX).toLongOrNull()
                        if (numericId != null) {
                            ListItem(
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                    headlineColor = MaterialTheme.colorScheme.onSurface,
                                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                headlineContent = { Text(pl.title) },
                                supportingContent = { Text(pl.subtitle.orEmpty()) },
                                leadingContent = {
                                    VelunaArtwork(
                                        url = pl.thumbnail,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp)
                                    )
                                },
                                modifier = Modifier
                                    .clickable {
                                        onPickPlaylist(numericId)
                                        onDismiss()
                                    }
                                    .testTag("pick_playlist_${pl.id}")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
fun CreateEditPlaylistDialog(
    initialTitle: String = "",
    initialDescription: String = "",
    initialCoverUri: String? = null,
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String, coverUri: String?) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    var coverUri by remember { mutableStateOf(initialCoverUri) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) coverUri = uri.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(stringResource(if (isEditing) R.string.playlist_dialog_edit_title else R.string.playlist_dialog_new_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isEditing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VelunaArtwork(
                            url = coverUri,
                            contentDescription = stringResource(R.string.playlist_dialog_cover_desc),
                            modifier = Modifier.size(64.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = velunaOutlinedButtonColors(),
                            border = velunaOutlinedButtonBorder(),
                            modifier = Modifier.testTag("edit_playlist_cover_btn")
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.playlist_dialog_choose_cover))
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.playlist_dialog_title_label)) },
                    colors = velunaOutlinedFieldColors(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_title_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.playlist_dialog_desc_label)) },
                    colors = velunaOutlinedFieldColors(),
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_desc_input")
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                colors = velunaPrimaryButtonColors(),
                onClick = {
                    onConfirm(title.trim(), description.trim(), coverUri)
                    onDismiss()
                },
                modifier = Modifier.testTag("playlist_dialog_confirm")
            ) {
                Text(stringResource(if (isEditing) R.string.action_save else R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
fun ImportPlaylistDialog(
    state: ImportDialogState,
    onDismiss: () -> Unit,
    onImportYouTube: (String) -> Unit,
    onImportFile: (Uri, String) -> Unit,
    onOpenPlaylist: (Long) -> Unit
) {
    val defaultPlaylistName = stringResource(R.string.import_default_name)
    var tab by remember { mutableIntStateOf(0) }
    var urlInput by remember { mutableStateOf("") }
    var filePlaylistName by remember { mutableStateOf(defaultPlaylistName) }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportFile(uri, filePlaylistName.ifBlank { defaultPlaylistName })
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.isImporting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(stringResource(R.string.import_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(R.string.import_tab_youtube)) }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(R.string.import_tab_csv_m3u)) }
                    )
                }

                if (tab == 0) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text(stringResource(R.string.import_yt_label)) },
                        colors = velunaOutlinedFieldColors(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_yt_url_input")
                    )
                    Button(
                        enabled = !state.isImporting && urlInput.isNotBlank(),
                        colors = velunaPrimaryButtonColors(),
                        onClick = { onImportYouTube(urlInput) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_yt_start_btn")
                    ) {
                        Text(stringResource(R.string.import_yt_button))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.import_csv_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = filePlaylistName,
                        onValueChange = { filePlaylistName = it },
                        label = { Text(stringResource(R.string.import_dest_title)) },
                        colors = velunaOutlinedFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        enabled = !state.isImporting,
                        colors = velunaOutlinedButtonColors(),
                        border = velunaOutlinedButtonBorder(!state.isImporting),
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_file_pick_btn")
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.import_choose_file))
                    }
                }

                if (state.isImporting || state.statusText.isNotBlank()) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(state.statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                if (!state.error.isNullOrBlank()) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (state.completedPlaylistId != null) {
                Button(
                    colors = velunaPrimaryButtonColors(),
                    onClick = {
                        onOpenPlaylist(state.completedPlaylistId)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("import_open_result_btn")
                ) {
                    Text(stringResource(R.string.import_open_playlist))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.isImporting,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
