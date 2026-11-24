package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.cloud.CloudAuthUiState
import com.example.data.local.BlockedArtistEntity
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaUserPrefs
import com.example.model.AudioQuality
import com.example.model.LyricsProviderInfo
import com.example.model.TasteProfile
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset
import com.example.ui.screens.settings.AccountSection
import com.example.ui.screens.settings.AppearanceSection
import com.example.ui.screens.settings.BackupSection
import com.example.ui.screens.settings.HiddenArtistsSection
import com.example.ui.screens.settings.LyricsProvidersSection
import com.example.ui.screens.settings.PlaybackSection
import com.example.ui.screens.settings.ScrobblingSection
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.rememberVelunaLazyListState
import com.example.util.SecurityValidators
import com.example.util.readContentUriText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun SettingsScreen(
    prefs: VelunaUserPrefs,
    blockedArtists: List<BlockedArtistEntity>,
    modifier: Modifier = Modifier,
    tasteProfile: TasteProfile? = null,
    cloudAuthState: CloudAuthUiState = CloudAuthUiState(),
    scrobblePendingCount: Int = 0,
    lastScrobbleError: String? = null,
    restoredLegacyBackupWarning: Boolean = false,
    onDismissLegacyBackupWarning: () -> Unit = {},
    onSignInWithGoogle: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onConfirmSignOutDiscard: () -> Unit = {},
    onDismissSignOutConfirm: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    onClearDeleteAccountError: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onMergeUnownedData: () -> Unit = {},
    onDiscardUnownedData: () -> Unit = {},
    onOpenTasteOnboarding: () -> Unit = {},
    onSetThemePreset: (VelunaThemePreset) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetCustomAccent: (String?) -> Unit,
    onSetAdaptToArtwork: (Boolean) -> Unit,
    onSetClassicPlayerLayout: (Boolean) -> Unit = {},
    onSetCornerRadius: (Int) -> Unit,
    onSetDisplayName: (String) -> Unit,
    onSetAudioQuality: (AudioQuality) -> Unit,
    onSetAutoplay: (Boolean) -> Unit,
    onSetLoudnessNormalization: (Boolean) -> Unit,
    onSetHideMusicVideos: (Boolean) -> Unit,
    onSetAllowMirrorFallbacks: (Boolean) -> Unit = {},
    onOpenEqualizer: () -> Unit,
    onUpdateLyricsProviders: (List<LyricsProviderInfo>) -> Unit,
    onSaveScrobbling: (Boolean, String, String, String, String, String) -> Unit,
    onUnblockArtist: (String) -> Unit,
    onBuildBackupJson: suspend () -> String,
    onRestoreBackupJson: (String) -> Unit,
    onStatusMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportedMsg = stringResource(R.string.settings_backup_exported)
    val providers = remember(prefs.lyricsProviderOrder) {
        UserPrefsRepository.parseProviderOrder(prefs.lyricsProviderOrder)
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val payload = onBuildBackupJson()
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("Could not open the selected file")
                        output.bufferedWriter().use { it.write(payload) }
                    }
                    onStatusMessage(exportedMsg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onStatusMessage(
                        context.getString(
                            R.string.settings_backup_export_failed,
                            SecurityValidators.safeUserErrorMessage(e, "Failed to export backup")
                        )
                    )
                }
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = readContentUriText(context, uri, MAX_BACKUP_BYTES)
                    if (text.isBlank()) throw IllegalArgumentException("The selected file is empty")
                    onRestoreBackupJson(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val errorMessage = e.message
                        ?.takeIf { it.startsWith("File exceeds the ") }
                        ?: SecurityValidators.safeUserErrorMessage(e, "Failed to restore backup")
                    onStatusMessage(
                        context.getString(
                            R.string.settings_backup_restore_failed,
                            errorMessage
                        )
                    )
                }
            }
        }
    }

    val listState = rememberVelunaLazyListState()
    val smoothFling = rememberVelunaFlingBehavior()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = smoothFling,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .testTag("settings_screen")
        ) {
            item(key = "settings_title", contentType = "title") {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            item(key = "settings_account", contentType = "account") {
                AccountSection(
                    prefs = prefs,
                    tasteProfile = tasteProfile,
                    cloudAuthState = cloudAuthState,
                    onSignInWithGoogle = onSignInWithGoogle,
                    onSignOut = onSignOut,
                    onConfirmSignOutDiscard = onConfirmSignOutDiscard,
                    onDismissSignOutConfirm = onDismissSignOutConfirm,
                    onDeleteAccount = onDeleteAccount,
                    onClearDeleteAccountError = onClearDeleteAccountError,
                    onSyncNow = onSyncNow,
                    onOpenTasteOnboarding = onOpenTasteOnboarding,
                    onSetDisplayName = onSetDisplayName,
                    onStatusMessage = onStatusMessage,
                    onMergeUnownedData = onMergeUnownedData,
                    onDiscardUnownedData = onDiscardUnownedData
                )
            }

            item(key = "settings_appearance", contentType = "appearance") {
                AppearanceSection(
                    prefs = prefs,
                    onSetThemePreset = onSetThemePreset,
                    onSetThemeMode = onSetThemeMode,
                    onSetCustomAccent = onSetCustomAccent,
                    onSetAdaptToArtwork = onSetAdaptToArtwork,
                    onSetClassicPlayerLayout = onSetClassicPlayerLayout,
                    onSetCornerRadius = onSetCornerRadius
                )
            }

            item(key = "settings_playback", contentType = "playback") {
                PlaybackSection(
                    prefs = prefs,
                    onSetAudioQuality = onSetAudioQuality,
                    onSetAutoplay = onSetAutoplay,
                    onSetLoudnessNormalization = onSetLoudnessNormalization,
                    onSetHideMusicVideos = onSetHideMusicVideos,
                    onSetAllowMirrorFallbacks = onSetAllowMirrorFallbacks,
                    onOpenEqualizer = onOpenEqualizer
                )
            }

            item(key = "settings_lyrics_providers", contentType = "lyrics_providers") {
                LyricsProvidersSection(
                    providers = providers,
                    onUpdateLyricsProviders = onUpdateLyricsProviders
                )
            }

            item(key = "settings_scrobbling", contentType = "scrobbling") {
                ScrobblingSection(
                    prefs = prefs,
                    scrobblePendingCount = scrobblePendingCount,
                    lastScrobbleError = lastScrobbleError,
                    onSaveScrobbling = onSaveScrobbling
                )
            }

            if (blockedArtists.isNotEmpty()) {
                item(key = "settings_hidden_artists", contentType = "hidden_artists") {
                    HiddenArtistsSection(
                        blockedArtists = blockedArtists,
                        onUnblockArtist = onUnblockArtist
                    )
                }
            }

            item(key = "settings_backup", contentType = "backup") {
                BackupSection(
                    restoredLegacyBackupWarning = restoredLegacyBackupWarning,
                    onDismissLegacyBackupWarning = onDismissLegacyBackupWarning,
                    onExportClick = { exportBackupLauncher.launch("veluna-backup.json") },
                    onRestoreClick = { restoreBackupLauncher.launch(arrayOf("application/json", "*/*")) }
                )
            }
        }
    }
}

private const val MAX_BACKUP_BYTES = 20 * 1024 * 1024
