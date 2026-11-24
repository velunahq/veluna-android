package com.example.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.VelunaUserPrefs
import com.example.ui.theme.velunaOutlinedFieldColors
import com.example.ui.theme.velunaPrimaryButtonColors

@Composable
internal fun ScrobblingSection(
    prefs: VelunaUserPrefs,
    scrobblePendingCount: Int,
    lastScrobbleError: String?,
    onSaveScrobbling: (Boolean, String, String, String, String, String) -> Unit
) {
    var scrobbleEnabled by remember(prefs.scrobblingEnabled) { mutableStateOf(prefs.scrobblingEnabled) }
    var listenBrainzToken by remember(prefs.listenBrainzToken) { mutableStateOf(prefs.listenBrainzToken) }
    var lastFmUser by remember(prefs.lastFmUsername) { mutableStateOf(prefs.lastFmUsername) }
    var lastFmKey by remember(prefs.lastFmApiKey) { mutableStateOf(prefs.lastFmApiKey) }
    var lastFmSecret by remember(prefs.lastFmApiSecret) { mutableStateOf(prefs.lastFmApiSecret) }
    var lastFmSession by remember(prefs.lastFmSessionKey) { mutableStateOf(prefs.lastFmSessionKey) }

    SettingsCard(title = stringResource(R.string.settings_card_scrobbling)) {
        SettingSwitchRow(
            title = stringResource(R.string.settings_enable_scrobbling),
            checked = scrobbleEnabled,
            onCheckedChange = { scrobbleEnabled = it },
            tag = "settings_scrobble_switch"
        )

        if (scrobblePendingCount > 0 || !lastScrobbleError.isNullOrBlank()) {
            Surface(
                color = if (!lastScrobbleError.isNullOrBlank()) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(10.dp)
                ) {
                    Text(
                        text = buildString {
                            if (scrobblePendingCount > 0) {
                                append(stringResource(R.string.settings_scrobble_pending, scrobblePendingCount))
                            }
                            if (!lastScrobbleError.isNullOrBlank()) {
                                if (isNotEmpty()) append(" • ")
                                append(stringResource(R.string.settings_scrobble_last_error, lastScrobbleError))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!lastScrobbleError.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("settings_scrobble_status")
                    )
                }
            }
        }

        OutlinedTextField(
            value = listenBrainzToken,
            onValueChange = { listenBrainzToken = it },
            label = { Text(stringResource(R.string.settings_listenbrainz_token)) },
            visualTransformation = PasswordVisualTransformation(),
            colors = velunaOutlinedFieldColors(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_listenbrainz_token_input")
        )
        OutlinedTextField(
            value = lastFmUser,
            onValueChange = { lastFmUser = it },
            label = { Text(stringResource(R.string.settings_lastfm_username)) },
            colors = velunaOutlinedFieldColors(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_lastfm_user_input")
        )
        OutlinedTextField(
            value = lastFmKey,
            onValueChange = { lastFmKey = it },
            label = { Text(stringResource(R.string.settings_lastfm_api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            colors = velunaOutlinedFieldColors(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_lastfm_key_input")
        )
        OutlinedTextField(
            value = lastFmSecret,
            onValueChange = { lastFmSecret = it },
            label = { Text(stringResource(R.string.settings_lastfm_api_secret)) },
            visualTransformation = PasswordVisualTransformation(),
            colors = velunaOutlinedFieldColors(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_lastfm_secret_input")
        )
        OutlinedTextField(
            value = lastFmSession,
            onValueChange = { lastFmSession = it },
            label = { Text(stringResource(R.string.settings_lastfm_session_key)) },
            visualTransformation = PasswordVisualTransformation(),
            colors = velunaOutlinedFieldColors(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_lastfm_session_input")
        )
        Button(
            onClick = {
                onSaveScrobbling(scrobbleEnabled, lastFmUser, lastFmKey, lastFmSecret, lastFmSession, listenBrainzToken)
            },
            colors = velunaPrimaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_scrobble_save_btn")
        ) {
            Text(stringResource(R.string.settings_save_scrobbling), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
