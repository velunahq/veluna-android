package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EqualizerPresets
import com.example.model.SongItem
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.velunaFilterChipBorder
import com.example.ui.theme.velunaFilterChipColors
import com.example.ui.theme.velunaSliderColors
import com.example.ui.viewmodel.CommentsUiState
import kotlin.math.roundToInt

@Composable
fun QueuePanel(
    queue: List<SongItem>,
    currentIndex: Int,
    sourceName: String?,
    onSelectIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.queue_up_next),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (!sourceName.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.queue_playing_from, sourceName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (queue.size > 1) {
                TextButton(
                    onClick = onClearQueue,
                    modifier = Modifier.testTag("queue_clear_btn")
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.queue_clear))
                }
            }
        }

        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val smoothFling = rememberVelunaFlingBehavior()
            LazyColumn(
                state = listState,
                flingBehavior = smoothFling,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = queue,
                    key = { idx, s -> "${idx}_${s.videoId}" },
                    contentType = { _, _ -> "queue_track" }
                ) { idx, song ->
                    val isCurrent = idx == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectIndex(idx) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("queue_item_$idx"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VelunaArtwork(
                            url = song.thumbnail,
                            contentDescription = song.title,
                            targetSizePx = 180,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (song.autoplay) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = stringResource(R.string.queue_autoplay),
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = song.artists,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isCurrent) {
                            IconButton(
                                onClick = { onRemoveIndex(idx) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("queue_remove_$idx")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.queue_remove),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = stringResource(R.string.queue_now_playing),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommentsPanel(
    state: CommentsUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    when {
        state.isLoading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.page == null -> {
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = state.error ?: stringResource(R.string.comments_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.page.disabled -> {
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.comments_disabled),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        else -> {
            val smoothFling = rememberVelunaFlingBehavior()
            LazyColumn(
                state = listState,
                flingBehavior = smoothFling,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = modifier.fillMaxSize()
            ) {
                if (!state.page.countText.isNullOrBlank()) {
                    item(key = "comments_count", contentType = "count_header") {
                        Text(
                            text = stringResource(R.string.comments_count, state.page.countText),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(
                    items = state.page.comments,
                    key = { it.id },
                    contentType = { "comment_card" }
                ) { comment ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            VelunaArtwork(
                                url = comment.authorAvatar,
                                contentDescription = comment.author,
                                isCircle = true,
                                targetSizePx = 144,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = comment.author,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = comment.publishedTime,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (comment.isPinned) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            Icons.Rounded.PushPin,
                                            contentDescription = stringResource(R.string.comments_pinned),
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (comment.isHearted) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Rounded.Favorite,
                                            contentDescription = stringResource(R.string.comments_hearted),
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = comment.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!comment.likeCount.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.ThumbUp, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = comment.likeCount,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerBottomSheet(
    enabled: Boolean,
    presetName: String,
    bandsDb: List<Float>,
    loudnessNormEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPreset: (String) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onToggleLoudness: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("equalizer_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.eq_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("eq_enable_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.eq_loudness_norm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = loudnessNormEnabled,
                    onCheckedChange = onToggleLoudness,
                    modifier = Modifier.testTag("eq_loudness_switch")
                )
            }

            val chipColors = velunaFilterChipColors()
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EqualizerPresets.presets.keys.forEach { name ->
                    val selected = presetName == name
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPreset(name) },
                        colors = chipColors,
                        border = velunaFilterChipBorder(selected),
                        label = { Text(name) },
                        modifier = Modifier.testTag("eq_preset_${name.lowercase().replace(' ', '_')}")
                    )
                }
            }

            var localBands by remember(bandsDb) { mutableStateOf(bandsDb) }
            EqualizerPresets.bandLabels.forEachIndexed { idx, hzLabel ->
                val value = localBands.getOrElse(idx) { 0f }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${hzLabel}Hz",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(56.dp)
                    )
                    Slider(
                        value = value,
                        onValueChange = { newVal ->
                            localBands = localBands.toMutableList().apply {
                                while (size <= idx) add(0f)
                                set(idx, newVal)
                            }
                        },
                        onValueChangeFinished = {
                            onBandChange(idx, localBands.getOrElse(idx) { 0f })
                        },
                        valueRange = -12f..12f,
                        enabled = enabled,
                        colors = velunaSliderColors(enabled),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("eq_band_slider_$idx")
                    )
                    Text(
                        text = "${value.roundToInt()} dB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
    }
}
