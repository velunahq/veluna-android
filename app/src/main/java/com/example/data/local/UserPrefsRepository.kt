package com.example.data.local

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.security.SecretVault
import com.example.data.security.SecretVaultCorruptionException
import com.example.model.AudioQuality
import com.example.model.EqualizerPresets
import com.example.model.LyricsProviderInfo
import com.example.model.TasteProfile
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset
import com.example.util.SecurityValidators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

private val Context.velunaPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "veluna_prefs")

@Immutable
@Serializable
data class VelunaUserPrefs(
    val themePreset: VelunaThemePreset = VelunaThemePreset.DEFAULT,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val customAccentHex: String? = null,
    val adaptToArtwork: Boolean = true,
    val cornerRadiusDp: Int = 12,
    val classicPlayerLayout: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val autoplay: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val loudnessNormalization: Boolean = true,
    val hideMusicVideos: Boolean = false,
    @Transient
    val allowMirrorFallbacks: Boolean = false,
    val eqEnabled: Boolean = false,
    val eqPresetName: String = "Flat",
    val eqBandsDb: List<Float> = EqualizerPresets.presets.getValue("Flat"),
    val lyricsProviderOrder: String = "boidu,lyricsplus,lrclib,youtube,simpmusic",
    val romanizeLyrics: Boolean = false,
    val scrobblingEnabled: Boolean = false,
    val lastFmUsername: String = "",
    val lastFmApiKey: String = "",
    val lastFmApiSecret: String = "",
    val lastFmSessionKey: String = "",
    val listenBrainzToken: String = "",
    val showMoodChips: Boolean = true,
    val showHeroArtworkBackdrop: Boolean = true,
    val userDisplayName: String = "Listener"
)

private data class VaultSecretsSnapshot(
    val lastFmApiKey: String = "",
    val lastFmApiSecret: String = "",
    val lastFmSessionKey: String = "",
    val listenBrainzToken: String = ""
)

@Serializable
data class SerializablePrefEntry(
    val key: String,
    val type: String,
    val value: String
)

enum class LocalDataProvenance {
    FRESH_LOCAL,
    ACCOUNT_OWNED,
    UNKNOWN_OR_LEGACY
}

class UserPrefsRepository(
    context: Context,
    val secretVault: SecretVault = SecretVault(context.applicationContext),
    private val dataStore: DataStore<Preferences> = context.applicationContext.velunaPrefsDataStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val vaultLoaded = AtomicBoolean(false)
    private val vaultState = MutableStateFlow(VaultSecretsSnapshot())
    private val _vaultCorrupted = MutableStateFlow(false)
    val vaultCorruptedFlow: StateFlow<Boolean> = _vaultCorrupted.asStateFlow()

    private object Keys {
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CUSTOM_ACCENT = stringPreferencesKey("custom_accent")
        val ADAPT_TO_ARTWORK = booleanPreferencesKey("adapt_to_artwork")
        val CORNER_RADIUS = intPreferencesKey("corner_radius")
        val CLASSIC_PLAYER = booleanPreferencesKey("classic_player")
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val AUTOPLAY = booleanPreferencesKey("autoplay")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val LOUDNESS_NORM = booleanPreferencesKey("loudness_norm")
        val HIDE_VIDEOS = booleanPreferencesKey("hide_videos")
        val ALLOW_MIRROR_FALLBACKS = booleanPreferencesKey("allow_mirror_fallbacks")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val LYRICS_PROVIDERS = stringPreferencesKey("lyrics_providers")
        val ROMANIZE_LYRICS = booleanPreferencesKey("romanize_lyrics")
        val SCROBBLING_ENABLED = booleanPreferencesKey("scrobbling_enabled")
        val LASTFM_USER = stringPreferencesKey("lastfm_user")
        val SHOW_MOOD_CHIPS = booleanPreferencesKey("show_mood_chips")
        val SHOW_HERO_BACKDROP = booleanPreferencesKey("show_hero_backdrop")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val TASTE_PROFILE_JSON = stringPreferencesKey("taste_profile_json")

        // Migration & Sync State
        val SECRETS_MIGRATED = booleanPreferencesKey("secrets_migrated")
        val LAST_PROFILE_PUSH_HASH = stringPreferencesKey("last_profile_push_hash")
        val LAST_SEEDS_PUSH_HASH = stringPreferencesKey("last_seeds_push_hash")
        val LAST_SCROBBLE_ERROR = stringPreferencesKey("last_scrobble_error")
        val LOCAL_LIKES_SCRUBBED = booleanPreferencesKey("local_likes_scrubbed")
        val PLAYLIST_TRACKS_V2_MIGRATED = booleanPreferencesKey("playlist_tracks_v2_migrated")
        val PREFS_UPDATED_AT = longPreferencesKey("prefs_updated_at")
        val ACTIVE_CLOUD_UID = stringPreferencesKey("active_cloud_uid")
        val LAST_SUCCESSFUL_SYNC_AT_MS = longPreferencesKey("last_successful_sync_at_ms")
        val LAST_SUCCESSFUL_SYNC_UID = stringPreferencesKey("last_successful_sync_uid")
        val PENDING_ACCOUNT_TRANSITION_UID = stringPreferencesKey("pending_account_transition_uid")
        val LOCAL_DATA_PROVENANCE = stringPreferencesKey("local_data_provenance")

        // Legacy DataStore keys (removed during migration, never written back)
        val LEGACY_CROSSFADE_SECS = intPreferencesKey("crossfade_secs")
        val LEGACY_TRANSLATE_LYRICS = booleanPreferencesKey("translate_lyrics")
        val LEGACY_LASTFM_KEY = stringPreferencesKey("lastfm_key")
        val LEGACY_LASTFM_SECRET = stringPreferencesKey("lastfm_secret")
        val LEGACY_LASTFM_SESSION = stringPreferencesKey("lastfm_session")
        val LEGACY_LISTENBRAINZ_TOKEN = stringPreferencesKey("listenbrainz_token")

        fun watermarkKey(collection: String) = longPreferencesKey("sync_watermark_$collection")
    }

    private fun readVaultSnapshot(): VaultSecretsSnapshot {
        return try {
            val all = secretVault.getAll()
            _vaultCorrupted.value = false
            VaultSecretsSnapshot(
                lastFmApiKey = all[SecretVault.KEY_LASTFM_KEY].orEmpty(),
                lastFmApiSecret = all[SecretVault.KEY_LASTFM_SECRET].orEmpty(),
                lastFmSessionKey = all[SecretVault.KEY_LASTFM_SESSION].orEmpty(),
                listenBrainzToken = all[SecretVault.KEY_LISTENBRAINZ_TOKEN].orEmpty()
            )
        } catch (_: SecretVaultCorruptionException) {
            // Preserve the unreadable file on disk rather than wiping or returning spoofed empty state silently.
            _vaultCorrupted.value = true
            VaultSecretsSnapshot()
        }
    }

    fun refreshVaultSecrets() {
        vaultLoaded.set(true)
        vaultState.value = readVaultSnapshot()
    }

    val prefsFlow: Flow<VelunaUserPrefs> = combine(
        dataStore.data,
        vaultState.onStart {
            if (!vaultLoaded.get()) {
                withContext(Dispatchers.IO) {
                    if (vaultLoaded.compareAndSet(false, true)) {
                        vaultState.value = readVaultSnapshot()
                    }
                }
            }
        }
    ) { p, secrets ->
        val preset = p[Keys.THEME_PRESET]?.let { id ->
            VelunaThemePreset.entries.find { it.id == id }
        } ?: VelunaThemePreset.DEFAULT
        val mode = p[Keys.THEME_MODE]?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()
        } ?: ThemeMode.DARK
        val quality = p[Keys.AUDIO_QUALITY]?.let { name ->
            runCatching { AudioQuality.valueOf(name) }.getOrNull()
        } ?: AudioQuality.HIGH
        val bands = p[Keys.EQ_BANDS]?.let { raw ->
            runCatching { json.decodeFromString<List<Float>>(raw) }.getOrNull()
        }?.let(::sanitizeEqBands) ?: EqualizerPresets.presets.getValue("Flat")
        val speed = p[Keys.PLAYBACK_SPEED]?.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f

        VelunaUserPrefs(
            themePreset = preset,
            themeMode = mode,
            customAccentHex = p[Keys.CUSTOM_ACCENT]?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) },
            adaptToArtwork = p[Keys.ADAPT_TO_ARTWORK] ?: true,
            cornerRadiusDp = (p[Keys.CORNER_RADIUS] ?: 12).coerceIn(4, 24),
            classicPlayerLayout = p[Keys.CLASSIC_PLAYER] ?: false,
            audioQuality = quality,
            autoplay = p[Keys.AUTOPLAY] ?: true,
            playbackSpeed = speed,
            loudnessNormalization = p[Keys.LOUDNESS_NORM] ?: true,
            hideMusicVideos = p[Keys.HIDE_VIDEOS] ?: false,
            allowMirrorFallbacks = p[Keys.ALLOW_MIRROR_FALLBACKS] ?: false,
            eqEnabled = p[Keys.EQ_ENABLED] ?: false,
            eqPresetName = p[Keys.EQ_PRESET]?.trim()?.take(40)?.ifBlank { "Flat" } ?: "Flat",
            eqBandsDb = bands,
            lyricsProviderOrder = normalizeProviderOrder(p[Keys.LYRICS_PROVIDERS]),
            romanizeLyrics = p[Keys.ROMANIZE_LYRICS] ?: false,
            scrobblingEnabled = p[Keys.SCROBBLING_ENABLED] ?: false,
            lastFmUsername = p[Keys.LASTFM_USER].orEmpty(),
            lastFmApiKey = secrets.lastFmApiKey,
            lastFmApiSecret = secrets.lastFmApiSecret,
            lastFmSessionKey = secrets.lastFmSessionKey,
            listenBrainzToken = secrets.listenBrainzToken,
            showMoodChips = p[Keys.SHOW_MOOD_CHIPS] ?: true,
            showHeroArtworkBackdrop = p[Keys.SHOW_HERO_BACKDROP] ?: true,
            userDisplayName = p[Keys.DISPLAY_NAME]?.trim()?.take(80)?.ifBlank { "Listener" } ?: "Listener"
        )
    }.distinctUntilChanged()

    val tasteProfileFlow: Flow<TasteProfile> = dataStore.data
        .map { p ->
            val raw = p[Keys.TASTE_PROFILE_JSON]
            val parsed = if (!raw.isNullOrBlank()) {
                runCatching { json.decodeFromString<TasteProfile>(raw) }.getOrNull()
            } else null
            val completed = p[Keys.ONBOARDING_COMPLETED] ?: parsed?.onboardingCompleted ?: false
            (parsed ?: TasteProfile()).copy(onboardingCompleted = completed)
        }
        .distinctUntilChanged()

    val lastScrobbleErrorFlow: Flow<String?> = dataStore.data
        .map { it[Keys.LAST_SCROBBLE_ERROR] }
        .distinctUntilChanged()

    suspend fun migrateSecretsAndPruneLegacyKeys() {
        val current = dataStore.data.first()
        if (current[Keys.SECRETS_MIGRATED] != true) {
            val migrated = buildMap {
                current[Keys.LEGACY_LASTFM_KEY]?.takeIf { it.isNotBlank() }?.let { put(SecretVault.KEY_LASTFM_KEY, it) }
                current[Keys.LEGACY_LASTFM_SECRET]?.takeIf { it.isNotBlank() }?.let { put(SecretVault.KEY_LASTFM_SECRET, it) }
                current[Keys.LEGACY_LASTFM_SESSION]?.takeIf { it.isNotBlank() }?.let { put(SecretVault.KEY_LASTFM_SESSION, it) }
                current[Keys.LEGACY_LISTENBRAINZ_TOKEN]?.takeIf { it.isNotBlank() }?.let { put(SecretVault.KEY_LISTENBRAINZ_TOKEN, it) }
            }
            if (migrated.isNotEmpty()) {
                // Persist to SecretVault BEFORE marking migrated or deleting legacy keys.
                secretVault.putAll(migrated)
            }
        }

        dataStore.edit { p ->
            p.remove(Keys.LEGACY_CROSSFADE_SECS)
            p.remove(Keys.LEGACY_TRANSLATE_LYRICS)
            p[Keys.SECRETS_MIGRATED] = true
            p.remove(Keys.LEGACY_LASTFM_KEY)
            p.remove(Keys.LEGACY_LASTFM_SECRET)
            p.remove(Keys.LEGACY_LASTFM_SESSION)
            p.remove(Keys.LEGACY_LISTENBRAINZ_TOKEN)
        }
        refreshVaultSecrets()
    }

    suspend fun saveTasteProfile(profile: TasteProfile, touchProfileTimestamp: Boolean = false) {
        val now = System.currentTimeMillis()
        dataStore.edit { p ->
            p[Keys.ONBOARDING_COMPLETED] = profile.onboardingCompleted
            p[Keys.TASTE_PROFILE_JSON] = json.encodeToString(profile)
            if (touchProfileTimestamp) {
                p[Keys.PREFS_UPDATED_AT] = now
            }
        }
    }

    suspend fun updateTasteProfile(
        touchProfileTimestamp: Boolean = false,
        transform: (TasteProfile) -> TasteProfile
    ) {
        val now = System.currentTimeMillis()
        dataStore.edit { p ->
            val raw = p[Keys.TASTE_PROFILE_JSON]
            val current = (if (!raw.isNullOrBlank()) runCatching { json.decodeFromString<TasteProfile>(raw) }.getOrNull() else null)
                ?: TasteProfile(onboardingCompleted = p[Keys.ONBOARDING_COMPLETED] ?: false)
            val updated = transform(current)
            p[Keys.ONBOARDING_COMPLETED] = updated.onboardingCompleted
            p[Keys.TASTE_PROFILE_JSON] = json.encodeToString(updated)
            if (touchProfileTimestamp || updated.selectedGenres != current.selectedGenres || updated.onboardingCompleted != current.onboardingCompleted) {
                p[Keys.PREFS_UPDATED_AT] = now
            }
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        updateTasteProfile(touchProfileTimestamp = true) { it.copy(onboardingCompleted = completed) }
    }

    suspend fun setThemePreset(preset: VelunaThemePreset) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.THEME_PRESET] = preset.id
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.THEME_MODE] = mode.name
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setCustomAccent(hex: String?) {
        val validHex = hex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }
        val now = System.currentTimeMillis()
        dataStore.edit {
            if (validHex == null) it.remove(Keys.CUSTOM_ACCENT)
            else it[Keys.CUSTOM_ACCENT] = validHex
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setAdaptToArtwork(enabled: Boolean) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.ADAPT_TO_ARTWORK] = enabled
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setCornerRadius(dp: Int) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.CORNER_RADIUS] = dp.coerceIn(4, 24)
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setClassicPlayerLayout(enabled: Boolean) {
        dataStore.edit { it[Keys.CLASSIC_PLAYER] = enabled }
    }

    suspend fun setAudioQuality(quality: AudioQuality) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.AUDIO_QUALITY] = quality.name
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setAutoplay(enabled: Boolean) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.AUTOPLAY] = enabled
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = if (speed.isFinite()) speed.coerceIn(0.5f, 2.0f) else 1.0f
        dataStore.edit { it[Keys.PLAYBACK_SPEED] = safeSpeed }
    }

    suspend fun setLoudnessNormalization(enabled: Boolean) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.LOUDNESS_NORM] = enabled
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setHideMusicVideos(enabled: Boolean) {
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.HIDE_VIDEOS] = enabled
            it[Keys.PREFS_UPDATED_AT] = now
        }
    }

    suspend fun setAllowMirrorFallbacks(enabled: Boolean) {
        dataStore.edit { it[Keys.ALLOW_MIRROR_FALLBACKS] = enabled }
    }

    suspend fun setEqualizer(enabled: Boolean, presetName: String, bands: List<Float>) {
        val safeBands = sanitizeEqBands(bands) ?: EqualizerPresets.presets.getValue("Flat")
        dataStore.edit {
            it[Keys.EQ_ENABLED] = enabled
            it[Keys.EQ_PRESET] = presetName.trim().take(40).ifBlank { "Custom" }
            it[Keys.EQ_BANDS] = json.encodeToString(safeBands)
        }
    }

    suspend fun setLyricsProvidersOrder(orderString: String) {
        dataStore.edit { it[Keys.LYRICS_PROVIDERS] = normalizeProviderOrder(orderString) }
    }

    suspend fun setRomanizeLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.ROMANIZE_LYRICS] = enabled }
    }

    suspend fun setScrobblingConfig(
        enabled: Boolean,
        lastFmUser: String,
        lastFmKey: String,
        lastFmSecret: String,
        lastFmSession: String,
        listenBrainzToken: String
    ) {
        secretVault.putAll(
            mapOf(
                SecretVault.KEY_LASTFM_KEY to lastFmKey.trim(),
                SecretVault.KEY_LASTFM_SECRET to lastFmSecret.trim(),
                SecretVault.KEY_LASTFM_SESSION to lastFmSession.trim(),
                SecretVault.KEY_LISTENBRAINZ_TOKEN to listenBrainzToken.trim()
            )
        )
        dataStore.edit {
            it[Keys.SCROBBLING_ENABLED] = enabled
            it[Keys.LASTFM_USER] = lastFmUser.trim()
            it.remove(Keys.LEGACY_LASTFM_KEY)
            it.remove(Keys.LEGACY_LASTFM_SECRET)
            it.remove(Keys.LEGACY_LASTFM_SESSION)
            it.remove(Keys.LEGACY_LISTENBRAINZ_TOKEN)
        }
        refreshVaultSecrets()
    }

    suspend fun setShowMoodChips(show: Boolean) {
        dataStore.edit { it[Keys.SHOW_MOOD_CHIPS] = show }
    }

    suspend fun setShowHeroArtworkBackdrop(show: Boolean) {
        dataStore.edit { it[Keys.SHOW_HERO_BACKDROP] = show }
    }

    suspend fun setDisplayName(name: String, updatedAtMs: Long = System.currentTimeMillis()) {
        dataStore.edit {
            it[Keys.DISPLAY_NAME] = name.trim().take(80).ifBlank { "Listener" }
            it[Keys.PREFS_UPDATED_AT] = updatedAtMs.coerceAtLeast(0L)
        }
    }

    suspend fun getPrefsUpdatedAt(): Long = dataStore.data.first()[Keys.PREFS_UPDATED_AT] ?: 0L

    suspend fun setPrefsUpdatedAt(epochMs: Long) {
        dataStore.edit { it[Keys.PREFS_UPDATED_AT] = epochMs.coerceAtLeast(0L) }
    }

    suspend fun applyCloudProfilePrefs(
        displayName: String,
        themePreset: VelunaThemePreset?,
        themeMode: ThemeMode?,
        customAccentHex: String?,
        adaptToArtwork: Boolean,
        cornerRadiusDp: Int,
        audioQuality: AudioQuality?,
        autoplay: Boolean,
        loudnessNormalization: Boolean,
        hideMusicVideos: Boolean,
        prefsUpdatedAtMs: Long
    ) {
        val validHex = customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }
        dataStore.edit { p ->
            p[Keys.DISPLAY_NAME] = displayName.trim().take(80).ifBlank { "Listener" }
            themePreset?.let { p[Keys.THEME_PRESET] = it.id }
            themeMode?.let { p[Keys.THEME_MODE] = it.name }
            if (validHex == null) p.remove(Keys.CUSTOM_ACCENT) else p[Keys.CUSTOM_ACCENT] = validHex
            p[Keys.ADAPT_TO_ARTWORK] = adaptToArtwork
            p[Keys.CORNER_RADIUS] = cornerRadiusDp.coerceIn(4, 24)
            audioQuality?.let { p[Keys.AUDIO_QUALITY] = it.name }
            p[Keys.AUTOPLAY] = autoplay
            p[Keys.LOUDNESS_NORM] = loudnessNormalization
            p[Keys.HIDE_VIDEOS] = hideMusicVideos
            p[Keys.PREFS_UPDATED_AT] = prefsUpdatedAtMs.coerceAtLeast(0L)
        }
    }

    suspend fun setLastScrobbleError(message: String?) {
        dataStore.edit {
            if (message.isNullOrBlank()) it.remove(Keys.LAST_SCROBBLE_ERROR)
            else it[Keys.LAST_SCROBBLE_ERROR] = message.take(240)
        }
    }

    suspend fun getLastProfilePushHash(): String? = dataStore.data.first()[Keys.LAST_PROFILE_PUSH_HASH]
    suspend fun setLastProfilePushHash(hash: String) {
        dataStore.edit { it[Keys.LAST_PROFILE_PUSH_HASH] = hash }
    }

    suspend fun getLastSeedsPushHash(): String? = dataStore.data.first()[Keys.LAST_SEEDS_PUSH_HASH]
    suspend fun setLastSeedsPushHash(hash: String) {
        dataStore.edit { it[Keys.LAST_SEEDS_PUSH_HASH] = hash }
    }

    suspend fun getSyncWatermark(collection: String): Long =
        dataStore.data.first()[Keys.watermarkKey(collection)] ?: 0L

    suspend fun setSyncWatermark(collection: String, epochMs: Long) {
        dataStore.edit { it[Keys.watermarkKey(collection)] = epochMs.coerceAtLeast(0L) }
    }

    suspend fun isLocalLikesScrubbed(): Boolean =
        dataStore.data.first()[Keys.LOCAL_LIKES_SCRUBBED] == true

    suspend fun markLocalLikesScrubbed() {
        dataStore.edit { it[Keys.LOCAL_LIKES_SCRUBBED] = true }
    }

    suspend fun isPlaylistTracksV2Migrated(): Boolean =
        dataStore.data.first()[Keys.PLAYLIST_TRACKS_V2_MIGRATED] == true

    suspend fun markPlaylistTracksV2Migrated() {
        dataStore.edit { it[Keys.PLAYLIST_TRACKS_V2_MIGRATED] = true }
    }

    suspend fun getLocalDataProvenance(): LocalDataProvenance? {
        val raw = dataStore.data.first()[Keys.LOCAL_DATA_PROVENANCE] ?: return null
        return runCatching { LocalDataProvenance.valueOf(raw) }.getOrNull()
    }

    suspend fun setLocalDataProvenance(provenance: LocalDataProvenance) {
        dataStore.edit { it[Keys.LOCAL_DATA_PROVENANCE] = provenance.name }
    }

    fun hasSyncableProfileState(p: Preferences): Boolean {
        if ((p[Keys.PREFS_UPDATED_AT] ?: 0L) > 0L) return true
        if (p[Keys.ONBOARDING_COMPLETED] == true) return true
        val customDisplayName = p[Keys.DISPLAY_NAME]?.trim()
        if (!customDisplayName.isNullOrBlank() && customDisplayName != "Listener") return true

        val tasteRaw = p[Keys.TASTE_PROFILE_JSON]
        val parsedTaste = if (!tasteRaw.isNullOrBlank()) {
            runCatching { json.decodeFromString<TasteProfile>(tasteRaw) }.getOrNull()
        } else null
        if (parsedTaste != null && (
                parsedTaste.onboardingCompleted ||
                    parsedTaste.selectedGenres.isNotEmpty() ||
                    parsedTaste.seedArtists.isNotEmpty() ||
                    parsedTaste.seedSongs.isNotEmpty()
                )
        ) {
            return true
        }

        if (p[Keys.THEME_PRESET]?.let { it != VelunaThemePreset.DEFAULT.id } == true) return true
        if (p[Keys.THEME_MODE]?.let { it != ThemeMode.DARK.name } == true) return true
        if (!p[Keys.CUSTOM_ACCENT].isNullOrBlank()) return true
        if (p[Keys.ADAPT_TO_ARTWORK] == false) return true
        if (p[Keys.CORNER_RADIUS]?.let { it != 12 } == true) return true
        if (p[Keys.AUDIO_QUALITY]?.let { it != AudioQuality.HIGH.name } == true) return true
        if (p[Keys.AUTOPLAY] == false) return true
        if (p[Keys.LOUDNESS_NORM] == false) return true
        if (p[Keys.HIDE_VIDEOS] == true) return true

        return false
    }

    suspend fun hasSyncableProfileState(): Boolean = hasSyncableProfileState(dataStore.data.first())

    /**
     * Records explicit local-data provenance on startup if not yet initialized:
     * - If an active or pending cloud UID exists -> ACCOUNT_OWNED
     * - If all synced Room tables, cloud-syncable profile/taste state, and legacy cloud sync markers are empty -> FRESH_LOCAL
     * - Otherwise (pre-provenance upgraded data or partial/untracked state) -> UNKNOWN_OR_LEGACY
     */
    suspend fun initializeInstallProvenanceIfClean(dao: VelunaDao) {
        val current = dataStore.data.first()
        if (current[Keys.LOCAL_DATA_PROVENANCE] != null) return

        val activeUid = current[Keys.ACTIVE_CLOUD_UID]?.takeIf { it.isNotBlank() }
        val pendingUid = current[Keys.PENDING_ACCOUNT_TRANSITION_UID]?.takeIf { it.isNotBlank() }
        if (activeUid != null || pendingUid != null) {
            dataStore.edit { p ->
                if (p[Keys.LOCAL_DATA_PROVENANCE] == null) {
                    p[Keys.LOCAL_DATA_PROVENANCE] = LocalDataProvenance.ACCOUNT_OWNED.name
                }
            }
            return
        }

        val hasSyncedTablesData = dao.getPlaylistsOnce().isNotEmpty() ||
            dao.getLikedSongsOnce().any { !it.videoId.startsWith(com.example.model.LOCAL_SONG_PREFIX) } ||
            dao.getBlockedArtistsOnce().isNotEmpty() ||
            dao.getAllTombstones().isNotEmpty() ||
            dao.hasAnySyncBaselines()

        val hasProfileData = hasSyncableProfileState(current)
        val hasLegacyMarkers = hasLegacyCloudSyncMarkers(targetUid = "")

        val resolved = if (!hasSyncedTablesData && !hasProfileData && !hasLegacyMarkers) {
            LocalDataProvenance.FRESH_LOCAL
        } else {
            LocalDataProvenance.UNKNOWN_OR_LEGACY
        }

        dataStore.edit { p ->
            if (p[Keys.LOCAL_DATA_PROVENANCE] == null) {
                p[Keys.LOCAL_DATA_PROVENANCE] = resolved.name
            }
        }
    }

    suspend fun getActiveCloudUid(): String? =
        dataStore.data.first()[Keys.ACTIVE_CLOUD_UID]?.takeIf { it.isNotBlank() }

    suspend fun setActiveCloudUid(uid: String?) {
        dataStore.edit { p ->
            if (uid.isNullOrBlank()) p.remove(Keys.ACTIVE_CLOUD_UID)
            else {
                p[Keys.ACTIVE_CLOUD_UID] = uid
                p[Keys.LOCAL_DATA_PROVENANCE] = LocalDataProvenance.ACCOUNT_OWNED.name
            }
        }
    }

    suspend fun getPendingAccountTransitionUid(): String? =
        dataStore.data.first()[Keys.PENDING_ACCOUNT_TRANSITION_UID]?.takeIf { it.isNotBlank() }

    suspend fun setPendingAccountTransitionUid(uid: String?) {
        dataStore.edit { p ->
            if (uid.isNullOrBlank()) p.remove(Keys.PENDING_ACCOUNT_TRANSITION_UID)
            else p[Keys.PENDING_ACCOUNT_TRANSITION_UID] = uid
        }
    }

    suspend fun hasLegacyCloudSyncMarkers(targetUid: String): Boolean {
        val p = dataStore.data.first()
        val hasWatermark = (p[Keys.watermarkKey(WATERMARK_LIKED)] ?: 0L) > 0L ||
            (p[Keys.watermarkKey(WATERMARK_PLAYLISTS)] ?: 0L) > 0L ||
            (p[Keys.watermarkKey(WATERMARK_SEEDS)] ?: 0L) > 0L ||
            (p[Keys.watermarkKey(WATERMARK_HIDDEN)] ?: 0L) > 0L
        val lastUid = p[Keys.LAST_SUCCESSFUL_SYNC_UID]?.takeIf { it.isNotBlank() }
        return hasWatermark ||
            p[Keys.LAST_PROFILE_PUSH_HASH] != null ||
            p[Keys.LAST_SEEDS_PUSH_HASH] != null ||
            p[Keys.LOCAL_LIKES_SCRUBBED] == true ||
            p[Keys.PLAYLIST_TRACKS_V2_MIGRATED] == true ||
            (lastUid != null && lastUid != targetUid)
    }

    suspend fun getLastSuccessfulSyncAtMs(uid: String): Long {
        val p = dataStore.data.first()
        return if (p[Keys.LAST_SUCCESSFUL_SYNC_UID] == uid) {
            p[Keys.LAST_SUCCESSFUL_SYNC_AT_MS] ?: 0L
        } else {
            0L
        }
    }

    suspend fun setLastSuccessfulSyncAtMs(uid: String, epochMs: Long) {
        dataStore.edit { p ->
            p[Keys.LAST_SUCCESSFUL_SYNC_UID] = uid
            p[Keys.LAST_SUCCESSFUL_SYNC_AT_MS] = epochMs.coerceAtLeast(0L)
        }
    }

    suspend fun clearAllSyncWatermarks() {
        dataStore.edit { p ->
            p.remove(Keys.watermarkKey(WATERMARK_LIKED))
            p.remove(Keys.watermarkKey(WATERMARK_PLAYLISTS))
            p.remove(Keys.watermarkKey(WATERMARK_SEEDS))
            p.remove(Keys.watermarkKey(WATERMARK_HIDDEN))
        }
    }

    /**
     * Clears only Google-account/cloud-sync state on ordinary sign-out.
     * Preserves device-only preferences, EQ, playback settings, and SecretVault scrobble credentials.
     */
    suspend fun resetCloudSyncStateForSignOut() {
        dataStore.edit { p ->
            p.remove(Keys.DISPLAY_NAME)
            p.remove(Keys.ONBOARDING_COMPLETED)
            p.remove(Keys.TASTE_PROFILE_JSON)
            p.remove(Keys.THEME_PRESET)
            p.remove(Keys.THEME_MODE)
            p.remove(Keys.CUSTOM_ACCENT)
            p.remove(Keys.ADAPT_TO_ARTWORK)
            p.remove(Keys.CORNER_RADIUS)
            p.remove(Keys.AUDIO_QUALITY)
            p.remove(Keys.AUTOPLAY)
            p.remove(Keys.LOUDNESS_NORM)
            p.remove(Keys.HIDE_VIDEOS)
            p.remove(Keys.LAST_PROFILE_PUSH_HASH)
            p.remove(Keys.LAST_SEEDS_PUSH_HASH)
            p.remove(Keys.LOCAL_LIKES_SCRUBBED)
            p.remove(Keys.PLAYLIST_TRACKS_V2_MIGRATED)
            p.remove(Keys.PREFS_UPDATED_AT)
            p.remove(Keys.ACTIVE_CLOUD_UID)
            p.remove(Keys.LAST_SUCCESSFUL_SYNC_AT_MS)
            p.remove(Keys.LAST_SUCCESSFUL_SYNC_UID)
            p.remove(Keys.watermarkKey(WATERMARK_LIKED))
            p.remove(Keys.watermarkKey(WATERMARK_PLAYLISTS))
            p.remove(Keys.watermarkKey(WATERMARK_SEEDS))
            p.remove(Keys.watermarkKey(WATERMARK_HIDDEN))
            p[Keys.LOCAL_DATA_PROVENANCE] = LocalDataProvenance.FRESH_LOCAL.name
        }
    }

    suspend fun commitQuarantineMergePrefs(
        uid: String,
        tasteProfile: TasteProfile?,
        displayName: String,
        prefsUpdatedAtMs: Long,
        themePreset: VelunaThemePreset? = null,
        themeMode: ThemeMode? = null,
        customAccentHex: String? = null,
        adaptToArtwork: Boolean? = null,
        cornerRadiusDp: Int? = null,
        audioQuality: AudioQuality? = null,
        autoplay: Boolean? = null,
        loudnessNormalization: Boolean? = null,
        hideMusicVideos: Boolean? = null,
        applyQuarantinedThemeAndAudio: Boolean = false
    ) {
        val validHex = customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }
        dataStore.edit { p ->
            if (tasteProfile != null) {
                p[Keys.ONBOARDING_COMPLETED] = tasteProfile.onboardingCompleted
                p[Keys.TASTE_PROFILE_JSON] = json.encodeToString(tasteProfile)
            }
            if (displayName.isNotBlank() && displayName != "Listener") {
                p[Keys.DISPLAY_NAME] = displayName.trim().take(80)
                p[Keys.PREFS_UPDATED_AT] = prefsUpdatedAtMs.coerceAtLeast(0L)
            } else if (prefsUpdatedAtMs > 0L) {
                p[Keys.PREFS_UPDATED_AT] = prefsUpdatedAtMs.coerceAtLeast(0L)
            }
            if (applyQuarantinedThemeAndAudio) {
                themePreset?.let { p[Keys.THEME_PRESET] = it.id }
                themeMode?.let { p[Keys.THEME_MODE] = it.name }
                if (validHex == null) p.remove(Keys.CUSTOM_ACCENT) else p[Keys.CUSTOM_ACCENT] = validHex
                adaptToArtwork?.let { p[Keys.ADAPT_TO_ARTWORK] = it }
                cornerRadiusDp?.let { p[Keys.CORNER_RADIUS] = it.coerceIn(4, 24) }
                audioQuality?.let { p[Keys.AUDIO_QUALITY] = it.name }
                autoplay?.let { p[Keys.AUTOPLAY] = it }
                loudnessNormalization?.let { p[Keys.LOUDNESS_NORM] = it }
                hideMusicVideos?.let { p[Keys.HIDE_VIDEOS] = it }
            }
            p.remove(Keys.PENDING_ACCOUNT_TRANSITION_UID)
            p[Keys.ACTIVE_CLOUD_UID] = uid
            p[Keys.LOCAL_DATA_PROVENANCE] = LocalDataProvenance.ACCOUNT_OWNED.name
        }
    }

    suspend fun commitQuarantineDiscardPrefs(uid: String) {
        dataStore.edit { p ->
            p.remove(Keys.DISPLAY_NAME)
            p.remove(Keys.ONBOARDING_COMPLETED)
            p.remove(Keys.TASTE_PROFILE_JSON)
            p.remove(Keys.THEME_PRESET)
            p.remove(Keys.THEME_MODE)
            p.remove(Keys.CUSTOM_ACCENT)
            p.remove(Keys.ADAPT_TO_ARTWORK)
            p.remove(Keys.CORNER_RADIUS)
            p.remove(Keys.AUDIO_QUALITY)
            p.remove(Keys.AUTOPLAY)
            p.remove(Keys.LOUDNESS_NORM)
            p.remove(Keys.HIDE_VIDEOS)
            p.remove(Keys.LAST_PROFILE_PUSH_HASH)
            p.remove(Keys.LAST_SEEDS_PUSH_HASH)
            p.remove(Keys.LOCAL_LIKES_SCRUBBED)
            p.remove(Keys.PLAYLIST_TRACKS_V2_MIGRATED)
            p.remove(Keys.PREFS_UPDATED_AT)
            p.remove(Keys.LAST_SUCCESSFUL_SYNC_AT_MS)
            p.remove(Keys.LAST_SUCCESSFUL_SYNC_UID)
            p.remove(Keys.PENDING_ACCOUNT_TRANSITION_UID)
            p.remove(Keys.watermarkKey(WATERMARK_LIKED))
            p.remove(Keys.watermarkKey(WATERMARK_PLAYLISTS))
            p.remove(Keys.watermarkKey(WATERMARK_SEEDS))
            p.remove(Keys.watermarkKey(WATERMARK_HIDDEN))
            p[Keys.ACTIVE_CLOUD_UID] = uid
            p[Keys.LOCAL_DATA_PROVENANCE] = LocalDataProvenance.ACCOUNT_OWNED.name
        }
    }

    /**
     * Explicit user/account DataStore key + SecretVault reset for permanent account deletion.
     * Leaves device-level EQ, playback speed, layout, lyrics, and mirror settings intact.
     */
    suspend fun resetUserKeys() {
        secretVault.wipeAll()
        _vaultCorrupted.value = false
        refreshVaultSecrets()
        dataStore.edit { p ->
            p.remove(Keys.DISPLAY_NAME)
            p.remove(Keys.ONBOARDING_COMPLETED)
            p.remove(Keys.TASTE_PROFILE_JSON)
            p.remove(Keys.THEME_PRESET)
            p.remove(Keys.THEME_MODE)
            p.remove(Keys.CUSTOM_ACCENT)
            p.remove(Keys.ADAPT_TO_ARTWORK)
            p.remove(Keys.CORNER_RADIUS)
            p.remove(Keys.AUDIO_QUALITY)
            p.remove(Keys.AUTOPLAY)
            p.remove(Keys.LOUDNESS_NORM)
            p.remove(Keys.HIDE_VIDEOS)
            p.remove(Keys.SCROBBLING_ENABLED)
            p.remove(Keys.LASTFM_USER)
            p.remove(Keys.LAST_PROFILE_PUSH_HASH)
            p.remove(Keys.LAST_SEEDS_PUSH_HASH)
            p.remove(Keys.LAST_SCROBBLE_ERROR)
            p.remove(Keys.LOCAL_LIKES_SCRUBBED)
            p.remove(Keys.PLAYLIST_TRACKS_V2_MIGRATED)
            p.remove(Keys.PREFS_UPDATED_AT)
            p.remove(Keys.ACTIVE_CLOUD_UID)
            p.remove(Keys.LAST_SUCCESSFUL_SYNC_AT_MS)
            p.remove(Keys.LAST_SUCCESSFUL_SYNC_UID)
            p.remove(Keys.PENDING_ACCOUNT_TRANSITION_UID)
            p.remove(Keys.watermarkKey(WATERMARK_LIKED))
            p.remove(Keys.watermarkKey(WATERMARK_PLAYLISTS))
            p.remove(Keys.watermarkKey(WATERMARK_SEEDS))
            p.remove(Keys.watermarkKey(WATERMARK_HIDDEN))
            p.remove(Keys.LEGACY_LASTFM_KEY)
            p.remove(Keys.LEGACY_LASTFM_SECRET)
            p.remove(Keys.LEGACY_LASTFM_SESSION)
            p.remove(Keys.LEGACY_LISTENBRAINZ_TOKEN)
            p[Keys.LOCAL_DATA_PROVENANCE] = LocalDataProvenance.FRESH_LOCAL.name
        }
    }

    suspend fun captureRawPreferencesSnapshot(): Preferences = dataStore.data.first()

    suspend fun restoreRawPreferencesSnapshot(snapshot: Preferences) {
        dataStore.edit { mutable ->
            mutable.clear()
            snapshot.asMap().forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                mutable[k as Preferences.Key<Any>] = v
            }
        }
    }

    suspend fun captureSerializablePreferencesSnapshot(): List<SerializablePrefEntry> {
        val excludedSecretKeys = setOf(
            Keys.LEGACY_LASTFM_KEY.name,
            Keys.LEGACY_LASTFM_SECRET.name,
            Keys.LEGACY_LASTFM_SESSION.name,
            Keys.LEGACY_LISTENBRAINZ_TOKEN.name
        )
        val raw = dataStore.data.first().asMap()
        return raw.entries.mapNotNull { (k, v) ->
            if (k.name in excludedSecretKeys) return@mapNotNull null
            when (v) {
                is String -> SerializablePrefEntry(k.name, "STRING", v)
                is Boolean -> SerializablePrefEntry(k.name, "BOOLEAN", v.toString())
                is Int -> SerializablePrefEntry(k.name, "INT", v.toString())
                is Long -> SerializablePrefEntry(k.name, "LONG", v.toString())
                is Float -> SerializablePrefEntry(k.name, "FLOAT", v.toString())
                else -> null
            }
        }
    }

    suspend fun restoreSerializablePreferencesSnapshot(entries: List<SerializablePrefEntry>) {
        val excludedSecretKeys = setOf(
            Keys.LEGACY_LASTFM_KEY.name,
            Keys.LEGACY_LASTFM_SECRET.name,
            Keys.LEGACY_LASTFM_SESSION.name,
            Keys.LEGACY_LISTENBRAINZ_TOKEN.name
        )
        dataStore.edit { mutable ->
            mutable.clear()
            entries.forEach { entry ->
                if (entry.key in excludedSecretKeys) return@forEach
                when (entry.type) {
                    "STRING" -> mutable[stringPreferencesKey(entry.key)] = entry.value
                    "BOOLEAN" -> entry.value.toBooleanStrictOrNull()?.let {
                        mutable[booleanPreferencesKey(entry.key)] = it
                    }
                    "INT" -> entry.value.toIntOrNull()?.let {
                        mutable[intPreferencesKey(entry.key)] = it
                    }
                    "LONG" -> entry.value.toLongOrNull()?.let {
                        mutable[longPreferencesKey(entry.key)] = it
                    }
                    "FLOAT" -> entry.value.toFloatOrNull()?.takeIf { it.isFinite() }?.let {
                        mutable[floatPreferencesKey(entry.key)] = it
                    }
                }
            }
        }
    }

    suspend fun applyBackupWithTaste(
        backup: VelunaUserPrefs,
        tasteProfile: TasteProfile?
    ) {
        val validHex = backup.customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }
        val now = System.currentTimeMillis()
        dataStore.edit {
            it[Keys.THEME_PRESET] = backup.themePreset.id
            it[Keys.THEME_MODE] = backup.themeMode.name
            if (validHex == null) it.remove(Keys.CUSTOM_ACCENT)
            else it[Keys.CUSTOM_ACCENT] = validHex
            it[Keys.ADAPT_TO_ARTWORK] = backup.adaptToArtwork
            it[Keys.CORNER_RADIUS] = backup.cornerRadiusDp.coerceIn(4, 24)
            it[Keys.AUDIO_QUALITY] = backup.audioQuality.name
            it[Keys.AUTOPLAY] = backup.autoplay
            it[Keys.LOUDNESS_NORM] = backup.loudnessNormalization
            it[Keys.HIDE_VIDEOS] = backup.hideMusicVideos
            it[Keys.DISPLAY_NAME] = backup.userDisplayName.trim().ifBlank { "Listener" }.take(80)
            if (tasteProfile != null) {
                it[Keys.ONBOARDING_COMPLETED] = tasteProfile.onboardingCompleted
                it[Keys.TASTE_PROFILE_JSON] = json.encodeToString(tasteProfile)
            }
            it[Keys.PREFS_UPDATED_AT] = now
            // Never restore scrobbling secrets or legacy keys from JSON backups.
        }
    }

    suspend fun applyBackup(backup: VelunaUserPrefs) {
        applyBackupWithTaste(backup, null)
    }

    companion object {
        const val WATERMARK_LIKED = "liked"
        const val WATERMARK_PLAYLISTS = "playlists"
        const val WATERMARK_SEEDS = "seeds"
        const val WATERMARK_HIDDEN = "hidden"

        val ALL_LYRICS_PROVIDERS = listOf(
            "boidu" to "Boidu (Apple Word-Timed)",
            "lyricsplus" to "LyricsPlus (KPOE)",
            "lrclib" to "LRCLIB",
            "youtube" to "YouTube Music",
            "simpmusic" to "SimpMusic"
        )

        fun sanitizeEqBands(raw: List<Float>): List<Float>? {
            if (raw.size != 10) return null
            return raw.map { band ->
                if (band.isFinite()) band.coerceIn(-12f, 12f) else 0f
            }
        }

        fun normalizeProviderOrder(raw: String?): String =
            serializeProviderOrder(parseProviderOrder(raw.orEmpty()))

        fun parseProviderOrder(raw: String): List<LyricsProviderInfo> {
            val seen = mutableSetOf<String>()
            val out = mutableListOf<LyricsProviderInfo>()
            val names = ALL_LYRICS_PROVIDERS.toMap()
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tok ->
                val enabled = !tok.startsWith("-")
                val id = tok.removePrefix("-")
                val name = names[id] ?: return@forEach
                if (seen.add(id)) {
                    out.add(LyricsProviderInfo(id = id, name = name, enabled = enabled))
                }
            }
            ALL_LYRICS_PROVIDERS.forEach { (id, name) ->
                if (seen.add(id)) {
                    out.add(LyricsProviderInfo(id = id, name = name, enabled = true))
                }
            }
            return out
        }

        fun serializeProviderOrder(list: List<LyricsProviderInfo>): String =
            list.joinToString(",") { if (it.enabled) it.id else "-${it.id}" }
    }
}
