package com.example.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.VelunaUserPrefs
import com.example.model.AudioQuality
import com.example.model.LyricsProviderInfo
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors

@Composable
internal fun PlaybackSection(
    prefs: VelunaUserPrefs,
    onSetAudioQuality: (AudioQuality) -> Unit,
    onSetAutoplay: (Boolean) -> Unit,
    onSetLoudnessNormalization: (Boolean) -> Unit,
    onSetHideMusicVideos: (Boolean) -> Unit,
    onSetAllowMirrorFallbacks: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit
) {
    SettingsCard(title = stringResource(R.string.settings_card_audio_playback)) {
        Text(stringResource(R.string.settings_streaming_quality), style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioQuality.entries.forEach { q ->
                val selected = prefs.audioQuality == q
                val rowBg = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
                Surface(
                    color = rowBg,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onSetAudioQuality(q) }
                        .testTag("settings_quality_${q.name.lowercase()}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = q.label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        SettingSwitchRow(stringResource(R.string.settings_autoplay), prefs.autoplay, onSetAutoplay, "settings_autoplay")
        SettingSwitchRow(stringResource(R.string.settings_loudness_norm), prefs.loudnessNormalization, onSetLoudnessNormalization, "settings_loudness")
        SettingSwitchRow(stringResource(R.string.settings_hide_videos), prefs.hideMusicVideos, onSetHideMusicVideos, "settings_hide_videos")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_allow_mirrors),
                checked = prefs.allowMirrorFallbacks,
                onCheckedChange = onSetAllowMirrorFallbacks,
                tag = "settings_allow_mirrors_switch"
            )
            Text(
                text = stringResource(R.string.settings_allow_mirrors_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedButton(
            onClick = onOpenEqualizer,
            colors = velunaOutlinedButtonColors(),
            border = velunaOutlinedButtonBorder(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_open_eq_btn")
        ) {
            Icon(Icons.Rounded.Equalizer, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            val eqLabel = if (prefs.eqEnabled) prefs.eqPresetName else stringResource(R.string.settings_equalizer_off)
            Text(
                text = stringResource(R.string.settings_equalizer_btn, eqLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun LyricsProvidersSection(
    providers: List<LyricsProviderInfo>,
    onUpdateLyricsProviders: (List<LyricsProviderInfo>) -> Unit
) {
    SettingsCard(title = stringResource(R.string.settings_card_lyrics_providers)) {
        Text(
            stringResource(R.string.settings_lyrics_providers_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        providers.forEachIndexed { idx, info ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = info.enabled,
                    onCheckedChange = { checked ->
                        val next = providers.toMutableList()
                        next[idx] = info.copy(enabled = checked)
                        onUpdateLyricsProviders(next)
                    },
                    modifier = Modifier.testTag("lyrics_provider_toggle_${info.id}")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = info.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = idx > 0,
                    onClick = {
                        val next = providers.toMutableList()
                        val item = next.removeAt(idx)
                        next.add(idx - 1, item)
                        onUpdateLyricsProviders(next)
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.settings_move_up))
                }
                IconButton(
                    enabled = idx < providers.lastIndex,
                    onClick = {
                        val next = providers.toMutableList()
                        val item = next.removeAt(idx)
                        next.add(idx + 1, item)
                        onUpdateLyricsProviders(next)
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.settings_move_down))
                }
            }
        }
    }
}
