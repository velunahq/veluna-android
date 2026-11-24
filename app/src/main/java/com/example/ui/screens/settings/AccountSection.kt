package com.example.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.cloud.CloudAuthUiState
import com.example.data.local.VelunaUserPrefs
import com.example.model.TasteProfile
import com.example.ui.components.VelunaArtwork
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors
import com.example.ui.theme.velunaOutlinedFieldColors
import com.example.ui.theme.velunaPrimaryButtonColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun AccountSection(
    prefs: VelunaUserPrefs,
    tasteProfile: TasteProfile?,
    cloudAuthState: CloudAuthUiState,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onConfirmSignOutDiscard: () -> Unit,
    onDismissSignOutConfirm: () -> Unit,
    onDeleteAccount: () -> Unit,
    onClearDeleteAccountError: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenTasteOnboarding: () -> Unit,
    onSetDisplayName: (String) -> Unit,
    onStatusMessage: (String) -> Unit,
    onMergeUnownedData: () -> Unit = {},
    onDiscardUnownedData: () -> Unit = {}
) {
    var displayName by remember(prefs.userDisplayName) { mutableStateOf(prefs.userDisplayName) }
    var deleteDialogStep by remember { mutableIntStateOf(0) } // 0=closed, 1=step1, 2=step2

    val nameSavedMsg = stringResource(R.string.settings_display_name_saved)

    SettingsCard(title = stringResource(R.string.settings_card_profile_sync)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (cloudAuthState.isSignedIn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!cloudAuthState.photoUrl.isNullOrBlank()) {
                            VelunaArtwork(
                                url = cloudAuthState.photoUrl,
                                contentDescription = cloudAuthState.displayName ?: stringResource(R.string.home_account_settings),
                                isCircle = true,
                                targetSizePx = 180,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccountCircle,
                                        contentDescription = stringResource(R.string.home_account_settings),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cloudAuthState.displayName?.takeIf { it.isNotBlank() } ?: prefs.userDisplayName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!cloudAuthState.email.isNullOrBlank()) {
                                Text(
                                    text = cloudAuthState.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val isSyncError = !cloudAuthState.isSyncing &&
                                cloudAuthState.syncStatus?.startsWith("Sync failed", ignoreCase = true) == true
                            val syncSubtitle = when {
                                cloudAuthState.isSyncing -> stringResource(R.string.settings_sync_syncing)
                                isSyncError -> cloudAuthState.syncStatus.orEmpty()
                                cloudAuthState.lastSyncedAtMs != null -> {
                                    val fmt = remember {
                                        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                                            .withZone(ZoneId.systemDefault())
                                    }
                                    stringResource(
                                        R.string.settings_sync_up_to_date,
                                        fmt.format(Instant.ofEpochMilli(cloudAuthState.lastSyncedAtMs))
                                    )
                                }
                                else -> cloudAuthState.syncStatus ?: stringResource(R.string.settings_sync_connected)
                            }
                            val statusColor = if (isSyncError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        cloudAuthState.isSyncing -> Icons.Rounded.CloudSync
                                        isSyncError -> Icons.Rounded.CloudOff
                                        else -> Icons.Rounded.CloudDone
                                    },
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = syncSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isSyncError) {
                                    TextButton(
                                        onClick = onSyncNow,
                                        modifier = Modifier.heightIn(min = 32.dp)
                                    ) {
                                        Text(stringResource(R.string.action_retry))
                                    }
                                }
                            }
                        }
                    }

                    if (cloudAuthState.hasUnownedDataConflict) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_unowned_conflict_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_unowned_data_title),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.settings_unowned_data_body,
                                        cloudAuthState.unownedDataItemCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = onMergeUnownedData,
                                        enabled = !cloudAuthState.isSyncing && !cloudAuthState.isAuthBusy,
                                        colors = velunaPrimaryButtonColors(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("settings_unowned_merge_btn")
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_unowned_data_merge),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = onDiscardUnownedData,
                                        enabled = !cloudAuthState.isSyncing && !cloudAuthState.isAuthBusy,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ),
                                        border = velunaOutlinedButtonBorder(!cloudAuthState.isSyncing),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("settings_unowned_discard_btn")
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_unowned_data_discard),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onSyncNow,
                            enabled = !cloudAuthState.isSyncing && !cloudAuthState.isAuthBusy && !cloudAuthState.isDeletingAccount,
                            colors = velunaOutlinedButtonColors(),
                            border = velunaOutlinedButtonBorder(!cloudAuthState.isSyncing && !cloudAuthState.isAuthBusy),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("settings_cloud_sync_btn")
                        ) {
                            if (cloudAuthState.isSyncing) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(if (cloudAuthState.isSyncing) R.string.settings_syncing else R.string.settings_sync_now),
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        TextButton(
                            onClick = onSignOut,
                            enabled = !cloudAuthState.isAuthBusy && !cloudAuthState.isDeletingAccount,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("settings_google_signout_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_signout), maxLines = 1, softWrap = false)
                        }
                    }

                    OutlinedButton(
                        onClick = { deleteDialogStep = 1 },
                        enabled = !cloudAuthState.isDeletingAccount && !cloudAuthState.isAuthBusy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = velunaOutlinedButtonBorder(!cloudAuthState.isDeletingAccount),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_delete_account_btn")
                    ) {
                        if (cloudAuthState.isDeletingAccount) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_deleting_account), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_delete_account_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_google_sync_title),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_google_sync_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = onSignInWithGoogle,
                        enabled = !cloudAuthState.isAuthBusy,
                        colors = velunaPrimaryButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_google_signin_btn")
                    ) {
                        if (cloudAuthState.isAuthBusy) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(if (cloudAuthState.isAuthBusy) R.string.settings_signing_in else R.string.settings_signin_google),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.settings_display_name)) },
                colors = velunaOutlinedFieldColors(),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_display_name_input")
            )
            Button(
                onClick = {
                    onSetDisplayName(displayName)
                    onStatusMessage(nameSavedMsg)
                },
                colors = velunaPrimaryButtonColors(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("settings_display_name_save")
            ) {
                Text(stringResource(R.string.action_save), maxLines = 1, softWrap = false)
            }
        }

        val g = tasteProfile?.selectedGenres?.size ?: 0
        val a = tasteProfile?.seedArtists?.size ?: 0
        val s = tasteProfile?.seedSongs?.size ?: 0
        val tasteSummary = if (g + a + s == 0) {
            stringResource(R.string.settings_tune_taste_empty)
        } else {
            "$g genres • $a artists • $s songs"
        }
        OutlinedButton(
            onClick = onOpenTasteOnboarding,
            colors = velunaOutlinedButtonColors(),
            border = velunaOutlinedButtonBorder(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_tune_taste_btn")
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_tune_taste_btn, tasteSummary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    // Two-step Account Deletion Dialog
    if (deleteDialogStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteDialogStep = 0 },
            title = { Text(stringResource(R.string.settings_delete_account_title_1)) },
            text = { Text(stringResource(R.string.settings_delete_account_body_1)) },
            confirmButton = {
                Button(
                    onClick = { deleteDialogStep = 2 },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.settings_delete_account_next))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogStep = 0 }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    } else if (deleteDialogStep == 2) {
        AlertDialog(
            onDismissRequest = { deleteDialogStep = 0 },
            title = { Text(stringResource(R.string.settings_delete_account_title_2)) },
            text = { Text(stringResource(R.string.settings_delete_account_body_2)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteDialogStep = 0
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("settings_confirm_delete_account_btn")
                ) {
                    Text(stringResource(R.string.settings_delete_account_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogStep = 0 }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Unsynced changes on Sign-Out Confirmation Dialog
    cloudAuthState.pendingSignOutConfirmCount?.let { count ->
        AlertDialog(
            onDismissRequest = onDismissSignOutConfirm,
            title = { Text(stringResource(R.string.settings_unsynced_signout_title)) },
            text = { Text(stringResource(R.string.settings_unsynced_signout_body, count)) },
            confirmButton = {
                Button(
                    onClick = onConfirmSignOutDiscard,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("settings_confirm_discard_signout_btn")
                ) {
                    Text(stringResource(R.string.settings_unsynced_signout_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSignOutConfirm) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Account Deletion Error / Retry Dialog
    cloudAuthState.deleteAccountError?.let { err ->
        AlertDialog(
            onDismissRequest = onClearDeleteAccountError,
            title = { Text(stringResource(R.string.settings_delete_error_title)) },
            text = { Text(err) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearDeleteAccountError()
                        onDeleteAccount()
                    }
                ) {
                    Text(
                        text = stringResource(
                            if (cloudAuthState.needsRetryAuthDelete) R.string.settings_delete_retry_btn
                            else R.string.action_retry
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onClearDeleteAccountError) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}
