package com.example.ui.components.nowplaying

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.SongItem
import com.example.playback.PlaybackPositionState
import com.example.playback.PlaybackUiState
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun DeckUtilityRow(
    song: SongItem,
    playbackState: PlaybackUiState,
    isDownloaded: Boolean,
    classicLayout: Boolean,
    onSetSpeed: (Float) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onToggleDownload: () -> Unit
) {
    var speedMenuOpen by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (classicLayout) 0.45f else 0.62f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                DeckUtilityPill(
                    icon = Icons.Rounded.Speed,
                    label = if (playbackState.speed != 1.0f) "${playbackState.speed}x" else null,
                    contentDescription = "Playback speed ${playbackState.speed}x",
                    active = playbackState.speed != 1.0f,
                    onClick = { speedMenuOpen = true },
                    tag = "now_playing_speed_btn"
                )
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { speedMenuOpen = false }
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                        DropdownMenuItem(
                            text = { Text("${sp}x") },
                            onClick = {
                                speedMenuOpen = false
                                onSetSpeed(sp)
                            }
                        )
                    }
                }
            }

            DeckUtilityPill(
                icon = Icons.Rounded.Timer,
                label = null,
                contentDescription = stringResource(R.string.player_sleep_timer),
                active = playbackState.sleepTimerRemainingMs != null,
                onClick = onOpenSleepTimer,
                tag = "now_playing_sleep_btn"
            )

            DeckUtilityPill(
                icon = Icons.Rounded.Equalizer,
                label = null,
                contentDescription = stringResource(R.string.player_equalizer),
                active = false,
                onClick = onOpenEqualizer,
                tag = "now_playing_eq_btn"
            )

            if (!song.isLocal) {
                DeckUtilityPill(
                    icon = if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    label = null,
                    contentDescription = stringResource(
                        if (isDownloaded) R.string.player_downloaded else R.string.player_download_offline
                    ),
                    active = isDownloaded,
                    onClick = onToggleDownload,
                    tag = "now_playing_download_btn"
                )
            }
        }
    }
}

@Composable
private fun DeckUtilityPill(
    icon: ImageVector,
    label: String?,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else Color.Transparent,
        shape = CircleShape,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(width = if (label != null) 68.dp else 48.dp, height = 48.dp)
                .padding(horizontal = if (label != null) 8.dp else 0.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            if (!label.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = tint,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
internal fun TrackSeekBar(
    positionFlow: StateFlow<PlaybackPositionState>,
    onSeekTo: (Long) -> Unit
) {
    val posState by positionFlow.collectAsStateWithLifecycle()
    var draggingSlider by remember { mutableStateOf(false) }
    var dragPosMs by remember { mutableFloatStateOf(0f) }

    val duration = posState.durationMs.coerceAtLeast(1L)
    val sliderPos = if (draggingSlider) dragPosMs else posState.positionMs.toFloat().coerceIn(0f, duration.toFloat())

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderPos,
            onValueChange = {
                draggingSlider = true
                dragPosMs = it
            },
            onValueChangeFinished = {
                draggingSlider = false
                onSeekTo(dragPosMs.toLong())
            },
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("now_playing_seek_slider")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMs(sliderPos.toLong()),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatMs(posState.durationMs),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSecs = ms / 1000L
    val h = totalSecs / 3600L
    val m = (totalSecs % 3600L) / 60L
    val s = totalSecs % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
