package com.example.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.BlockedArtistEntity
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors
import com.example.ui.theme.velunaPrimaryButtonColors

@Composable
internal fun HiddenArtistsSection(
    blockedArtists: List<BlockedArtistEntity>,
    onUnblockArtist: (String) -> Unit
) {
    SettingsCard(title = stringResource(R.string.settings_card_hidden_artists)) {
        blockedArtists.forEach { b ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = b.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { onUnblockArtist(b.name) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_unhide), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun BackupSection(
    restoredLegacyBackupWarning: Boolean,
    onDismissLegacyBackupWarning: () -> Unit,
    onExportClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    SettingsCard(title = stringResource(R.string.settings_card_backup)) {
        if (restoredLegacyBackupWarning) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_legacy_backup_warning")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_legacy_backup_warning_title),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_legacy_backup_warning_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onDismissLegacyBackupWarning) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
        }

        Text(
            stringResource(R.string.settings_backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onExportClick,
                colors = velunaPrimaryButtonColors(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("settings_backup_export_btn")
            ) {
                Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_backup_export), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onRestoreClick,
                colors = velunaOutlinedButtonColors(),
                border = velunaOutlinedButtonBorder(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("settings_backup_restore_btn")
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_backup_restore), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
