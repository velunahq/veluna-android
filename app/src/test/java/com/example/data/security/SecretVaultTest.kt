package com.example.data.security

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPrefsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class SecretVaultTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tempDir = File(context.cacheDir, "vault_test_${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun secretVault_putGetDeleteAndWipeAll() {
        val storage = File(tempDir, "secrets.bin")
        val cipher = InMemoryAesGcmVaultCipher()
        val vault = SecretVault(storage, cipher)

        vault.put(SecretVault.KEY_LISTENBRAINZ_TOKEN, "lb_token_123")
        vault.put(SecretVault.KEY_LASTFM_KEY, "lfm_key_abc")

        // Verify stored file on disk does not contain plaintext secret
        val rawOnDisk = storage.readText(Charsets.UTF_8)
        assertThat(rawOnDisk).doesNotContain("lb_token_123")
        assertThat(rawOnDisk).doesNotContain("lfm_key_abc")

        assertThat(vault.get(SecretVault.KEY_LISTENBRAINZ_TOKEN)).isEqualTo("lb_token_123")
        assertThat(vault.getAll()).containsExactly(
            SecretVault.KEY_LISTENBRAINZ_TOKEN, "lb_token_123",
            SecretVault.KEY_LASTFM_KEY, "lfm_key_abc"
        )

        vault.delete(SecretVault.KEY_LASTFM_KEY)
        assertThat(vault.get(SecretVault.KEY_LASTFM_KEY)).isNull()

        vault.wipeAll()
        assertThat(storage.exists()).isFalse()
        assertThat(vault.getAll()).isEmpty()
    }

    @Test
    fun userPrefsMigration_movesSecretsToVaultAndDropsLegacyKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ds = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir, "legacy_prefs.preferences_pb") }
        )
        val legacyCrossfade = intPreferencesKey("crossfade_secs")
        val legacyTranslate = booleanPreferencesKey("translate_lyrics")
        val legacyLfmKey = stringPreferencesKey("lastfm_key")
        val legacyLfmSecret = stringPreferencesKey("lastfm_secret")
        val legacyLfmSession = stringPreferencesKey("lastfm_session")
        val legacyLbToken = stringPreferencesKey("listenbrainz_token")

        ds.edit { p ->
            p[legacyCrossfade] = 6
            p[legacyTranslate] = true
            p[legacyLfmKey] = "migrated_key"
            p[legacyLfmSecret] = "migrated_secret"
            p[legacyLfmSession] = "migrated_session"
            p[legacyLbToken] = "migrated_lb_token"
        }

        val vault = SecretVault(File(tempDir, "migrated_secrets.bin"), InMemoryAesGcmVaultCipher())
        val repo = UserPrefsRepository(context, vault, ds)
        repo.migrateSecretsAndPruneLegacyKeys()

        val afterPrefs = repo.prefsFlow.first()
        assertThat(afterPrefs.lastFmApiKey).isEqualTo("migrated_key")
        assertThat(afterPrefs.lastFmApiSecret).isEqualTo("migrated_secret")
        assertThat(afterPrefs.lastFmSessionKey).isEqualTo("migrated_session")
        assertThat(afterPrefs.listenBrainzToken).isEqualTo("migrated_lb_token")

        val rawData = ds.data.first()
        assertThat(rawData.contains(legacyCrossfade)).isFalse()
        assertThat(rawData.contains(legacyTranslate)).isFalse()
        assertThat(rawData.contains(legacyLfmKey)).isFalse()
        assertThat(rawData.contains(legacyLfmSecret)).isFalse()
        assertThat(rawData.contains(legacyLfmSession)).isFalse()
        assertThat(rawData.contains(legacyLbToken)).isFalse()
    }
}
