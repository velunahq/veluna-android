package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.UserPrefsRepository
import com.example.model.LyricLine
import com.example.model.LyricWord
import com.example.playback.PlaybackPositionState
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.viewmodel.LyricsUiState
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedLyricsPanel(
    lyricsState: LyricsUiState,
    positionFlow: StateFlow<PlaybackPositionState>,
    romanizeEnabled: Boolean,
    onToggleRomanize: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onResetPinned: () -> Unit,
    onNudgeOffset: (Long) -> Unit,
    onSeekToLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    var providerMenuOpen by remember { mutableStateOf(false) }
    val data = lyricsState.data

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AssistChip(
                    onClick = { providerMenuOpen = true },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        leadingIconContentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    label = {
                        Text(
                            text = data?.source ?: stringResource(R.string.lyrics_source_default),
                            maxLines = 1
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("lyrics_source_picker")
                )
                DropdownMenu(
                    expanded = providerMenuOpen,
                    onDismissRequest = { providerMenuOpen = false }
                ) {
                    UserPrefsRepository.ALL_LYRICS_PROVIDERS.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                providerMenuOpen = false
                                onSelectProvider(id)
                            }
                        )
                    }
                    if (data?.pinned == true) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lyrics_reset_auto)) },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            onClick = {
                                providerMenuOpen = false
                                onResetPinned()
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (data?.synced == true) {
                    TextButton(
                        onClick = { onNudgeOffset(-250L) },
                        modifier = Modifier.testTag("lyrics_offset_minus")
                    ) { Text(stringResource(R.string.lyrics_offset_minus)) }
                    Text(
                        text = "${data.offsetMs / 1000.0}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { onNudgeOffset(250L) },
                        modifier = Modifier.testTag("lyrics_offset_plus")
                    ) { Text(stringResource(R.string.lyrics_offset_plus)) }
                } else if (data != null && !data.instrumental && data.lines.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.lyrics_plain),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                val romanizeLabel = stringResource(R.string.lyrics_romanize)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(romanizeLabel) } },
                    state = rememberTooltipState()
                ) {
                    IconButton(
                        onClick = onToggleRomanize,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("lyrics_toggle_romanize")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = romanizeLabel,
                            tint = if (romanizeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        when {
            lyricsState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            data == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            data.instrumental -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_instrumental),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val syncedLines = remember(data.lines, data.synced) {
                    if (data.synced) data.lines else emptyList()
                }
                val activeIndex by rememberActiveLyricIndex(syncedLines, data.offsetMs, positionFlow)

                LaunchedEffect(activeIndex, data.synced) {
                    if (data.synced && activeIndex >= 0) {
                        runCatching {
                            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                        }
                    }
                }

                val smoothFling = rememberVelunaFlingBehavior()
                LazyColumn(
                    state = listState,
                    flingBehavior = smoothFling,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = data.lines,
                        key = { idx, line -> "${idx}_${line.timeMs ?: -1L}" },
                        contentType = { _, _ -> "lyric_line" }
                    ) { idx, line ->
                        val isActive = data.synced && idx == activeIndex
                        val isPast = data.synced && idx < activeIndex
                        val canSeek = data.synced && line.timeMs != null
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable(enabled = canSeek) {
                                    line.timeMs?.let { onSeekToLine((it + data.offsetMs).coerceAtLeast(0L)) }
                                }
                                .padding(vertical = 4.dp, horizontal = 6.dp)
                                .alpha(
                                    when {
                                        !data.synced -> 0.96f
                                        isActive -> 1f
                                        isPast -> 0.62f
                                        else -> 0.80f
                                    }
                                )
                        ) {
                            if (isActive && line.words.isNotEmpty()) {
                                ActiveKaraokeLyricLine(
                                    words = line.words,
                                    offsetMs = data.offsetMs,
                                    positionFlow = positionFlow
                                )
                            } else {
                                Text(
                                    text = line.text,
                                    style = if (isActive) {
                                        MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                                    } else {
                                        MaterialTheme.typography.titleLarge
                                    },
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (romanizeEnabled && !line.romanized.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = line.romanized,
                                    style = MaterialTheme.typography.bodyMedium,
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

@Composable
private fun rememberActiveLyricIndex(
    lines: List<LyricLine>,
    offsetMs: Long,
    positionFlow: StateFlow<PlaybackPositionState>
): State<Int> {
    val pos by positionFlow.collectAsStateWithLifecycle()
    return remember(lines, offsetMs) {
        derivedStateOf {
            lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= pos.positionMs - offsetMs }
        }
    }
}

@Composable
private fun ActiveKaraokeLyricLine(
    words: List<LyricWord>,
    offsetMs: Long,
    positionFlow: StateFlow<PlaybackPositionState>
) {
    val pos by positionFlow.collectAsStateWithLifecycle()
    val reachedCount by remember(words, offsetMs) {
        derivedStateOf {
            val effectivePos = pos.positionMs - offsetMs
            words.count { effectivePos >= it.startMs }
        }
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val upcomingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val annotated = remember(words, reachedCount, primaryColor, upcomingColor) {
        buildAnnotatedString {
            words.forEachIndexed { wIdx, w ->
                if (wIdx > 0) append(" ")
                val reached = wIdx < reachedCount
                withStyle(
                    SpanStyle(
                        color = if (reached) primaryColor else upcomingColor,
                        fontWeight = if (reached) FontWeight.Bold else FontWeight.Medium
                    )
                ) {
                    append(w.text)
                }
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.headlineMedium
    )
}
