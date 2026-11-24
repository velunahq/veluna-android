# Veluna (Android)

[![Veluna CI & Release APK](../../actions/workflows/release-apk.yml/badge.svg)](../../actions/workflows/release-apk.yml)

Native Android music streaming and offline library app built with Kotlin, Jetpack Compose, Material Design 3, Media3 ExoPlayer, Room, DataStore, Android Keystore (`SecretVault`), and Firebase.

## Download & Install (Android 8.0+ / API 26+)

Signed builds are published only from version tags (`v*`) on GitHub Releases. Commits on `main` do not publish an installable APK. Tagged builds use the Firebase configuration and stable signing certificate configured for the app, so Google sign-in works in the downloaded build.

Signed, R8-minified release builds (`com.veluna.music`) will be published on **GitHub Releases** from `v*` version tags:

- **[Browse Releases](../../releases)** and download **`veluna-android.apk`** from the tagged release assets.

### Verify a Tagged Release APK

For signed `v*` release APKs, verify the signing certificate SHA-256 digest using `apksigner` from the Android SDK Build-Tools:

```bash
apksigner verify --print-certs veluna-android.apk
```

Compare the printed `Signer #1 certificate SHA-256 digest` against the release certificate SHA-256 published in [`RELEASE.md`](RELEASE.md) and the `RELEASE_CERT_SHA256` repository variable. See [`RELEASE.md`](RELEASE.md) for full verification and signing details.

## Features

- **Home & Discovery**: Time-of-day greeting, mood/genre filter chips, pinned Shortcuts, Jump Back In, Familiar Artists, Quick Picks, and YouTube Music InnerTube shelves.
- **Search & Moods**: Debounced search suggestions, categorized results (Top, Songs, Videos, Albums, Artists, Playlists), local search history, and Moods & Genres tiles.
- **Library & Offline Playback**: Local playlists with Photo Picker cover editing, `On Repeat` auto-playlist, `Liked Music`, saved albums, followed artists, offline track downloads, and SAF local audio file/folder playback.
- **Player & Audio Engine**: Media3 `MediaSessionService` foreground playback with `WAKE_MODE_NETWORK`, dynamic artwork wash, Queue + Autoplay radio continuation, 10-band Equalizer presets, loudness normalization, playback speed, sleep timer, and toggleable classic/modern Now Playing layouts.
- **Synced Lyrics & Comments**: Multi-provider lyrics waterfall (`Boidu`, `LyricsPlus`, `LRCLIB`, `YouTube Music`, `SimpMusic`) with click-to-seek, timing offset nudge, kana/hangul romanization (`Romanize lyrics`), and YouTube track comments.
- **Listening Stats & History**: Range-filtered SQL aggregations (`Week`, `Month`, `Year`, `All`), daily bar chart, 7×24 weekday-hour heatmap, streaks, top songs/artists/albums, and listening history.
- **Import & Export**: Export any playlist as **M3U8 (`#EXTM3U`)** or **Exportify-compatible CSV** (`title,artists,album,duration_secs,video_id`); import playlists from **M3U/M3U8**, **CSV (Exportify)**, or **YouTube / YouTube Music URLs**.
- **Themes & Encrypted Scrobbling**: 7 theme presets, Light/Dark/System modes, artwork-adaptive or custom accent colors, and Last.fm + ListenBrainz scrobbling with persistent offline retry queue and Android Keystore AES-256-GCM credential storage.

## Data Storage, Cloud Sync & Backup

### Cloud-Synced Data (Firebase Firestore)

When signed in with Google (via Android Credential Manager), Veluna syncs account data under `users/{uid}`:

- Display profile (`userDisplayName`, Google profile photo URL), syncable appearance/playback preferences (`themePreset`, `themeMode`, `customAccentHex`, `adaptToArtwork`, `cornerRadiusDp`, `audioQuality`, `autoplay`, `loudnessNormalization`, `hideMusicVideos` with bidirectional `prefsUpdatedAt` LWW), and onboarding taste profile / seeds (preserving real per-seed `updatedAtMs` timestamps)
- Liked YouTube songs (`LOCAL:` device tracks are strictly blocked from cloud sync)
- Custom playlists keyed by persistent Room `cloudKey` UUIDs (`pl_<uuid>`) with monotonic `syncRevision` tracking and atomic versioned snapshots (`playlists/{key}/snapshots/{snapshotId}/tracks/{trackDocId}` plus backward-compatible fallback reads of legacy `tracks`)
- Hidden / blocked artists

### Device-Only Data

The following stay on your device and are not uploaded to Firestore. Device backups may include some preferences; importing a backup does not change the third-party mirror opt-in:

- Local device audio files (`local_tracks`), offline downloaded tracks/files (`downloaded_tracks`), and lyrics cache (`lyrics_cache`)
- Listening history/stats (`listens`, `listen_songs`), search history, saved albums, followed artists, shortcuts, and pending scrobble retry queue (`pending_scrobbles`)
- Device-specific preferences (`playbackSpeed`, `classicPlayerLayout`, `equalizer`, `lyricsProviderOrder`, `romanizeLyrics`, `allowMirrorFallbacks`, `showMoodChips`, `showHeroArtworkBackdrop`, `scrobblingEnabled`) are not synced to Firestore. Backups may include some of them, but restore leaves the current device values unchanged.
- Scrobble credentials (API key, API secret, session key, ListenBrainz token), stored in `SecretVault` (`noBackupFilesDir/secrets.bin`) encrypted with Android Keystore AES-256-GCM and excluded from Android Auto Backup and Device Transfer

### Sync Baselines, Conflict Merge & Tombstone Retention

- **Strict UID Isolation & Sync Baselines**: Active cloud session ownership is tracked via `ACTIVE_CLOUD_UID`. Switching Google accounts without an intermediate sign-out automatically clears synced local tables and watermarks before syncing the incoming account. Room `sync_baselines` (scoped by `userId` and carrying `snapshotJson` baselines) durably track the last synced state, `revision`, `deleted` flag, and `clientUpdatedAt` for each entity so offline edits and deletions survive restarts and increment the pre-sign-out unsynced change counter accurately.
- **3-Way Playlist Merge & Versioned Snapshots**: Concurrent offline edits to the same playlist resolve via baseline-aware 3-way merge (`PlaylistThreeWayMerger`), preserving concurrent track additions and deletions across devices while publishing immutable `COMMITTED` snapshots (`snapshots/{snapshotId}/tracks`) before advancing the parent `activeSnapshotId` pointer inside a Firestore transaction. Readers validate snapshot `COMMITTED` state and `trackCount` before accepting a playlist revision.
- **Cover Lifecycle & Backup Exclusion**: Local Photo Picker playlist covers are validated by magic bytes and decoded bounds (`<= 8 MB`, `<= 4096×4096`), written atomically under `filesDir/playlist_covers`, and excluded from Android Auto Backup and Device Transfer (`backup_rules.xml`, `data_extraction_rules.xml`). Local `file://` covers preserve any existing remote HTTPS `remoteCoverUri` rather than blanking cloud artwork, while explicit cover removals set `coverRemoved = true` and propagate across devices.
- **Tombstone Retention, Watermark Overlap & Compaction Recovery**: Incremental queries apply a 10-second `updatedAt` overlap window so near-simultaneous writes are never skipped. Soft tombstones are retained for **90 days** before cleanup, and purge operations advance monotonic `tombstoneCompactedBeforeMs` / `syncGeneration` metadata on `users/{uid}` so stale offline clients force a full fetch and prune unchanged compacted items without resurrecting deleted records. Conflict LWW timestamps remain bounded against future clock skew (`<= request.time + 5m`).

### Backup v3 (JSON Export & Recoverable Restore)

- Exports playlists (`cloudKey`, sanitized `https://` `coverUri`, and portable `coverImageBase64` for local covers `<= 512 KB`), liked tracks, taste profile, saved albums, followed artists, shortcuts, recent search history (up to 100), listening history (last 90 days), and theme/playback preferences.
- **Scrobble secrets are never exported.** All payloads, track/playlist identifiers, and `coverImageBase64` image bytes are validated upfront before modifying storage; restore snapshots all 12 Room tables and DataStore preferences and rolls back atomically (and deletes newly created cover files) on any write failure. Preferences are clamped to valid bounds and playlists deduplicate by `cloudKey` and `(normalizedTitle, trackSignature)`.
- Legacy v1 and v2 backups remain importable; v1 backups trigger an in-app warning banner advising you to delete unredacted v1 files.

### Account Sign-Out & Deletion

- **Ordinary Sign-Out**: Cancels background debounce watchers, flushes pending sync changes (bounded by timeout; if changes remain or flush times out, surfaces the unsynced count dialog to either keep working or discard), wipes synced account tables (`liked_songs`, `local_playlists`, `blocked_artists`, `sync_tombstones`, `sync_baselines`) and cloud sync watermarks/display name, and signs out of Firebase and Credential Manager. Signing out keeps listening history, search history, saved albums, followed artists and shortcuts on this device. A later sign-in on the same device can see them.
- **In-App Deletion**: Open **Settings → Profile & Cloud Sync → Delete Account & Cloud Data** for a two-step confirmation that re-authenticates with Google (if required), removes all Firestore documents and subcollections under `users/{uid}`, deletes the Firebase Auth user, and wipes all account Room tables (including `listens`, `saved_albums`, `followed_artists`, `shortcuts`, `search_history`, `pending_scrobbles`) plus `SecretVault` while still keeping offline downloaded media, local audio files, and `lyrics_cache` intact.
- **Web Deletion Path**: If distributing publicly on Google Play or an external store, host a static account-deletion instruction page referencing **Veluna** and the in-app path above (along with a support contact) and link it in your store listing's Data Safety / Account Deletion URL field.

## Third-Party Services & Privacy

Veluna communicates with the following external services depending on features you use:

| Service | Host(s) | Purpose & Privacy Notes |
|---|---|---|
| **YouTube Music (InnerTube)** | `music.youtube.com`, `googlevideo.com`, `i.ytimg.com`, `yt3.ggpht.com`, `lh3.googleusercontent.com` | Search, browse shelves, comments, track metadata, primary audio stream resolution, and thumbnails. |
| **Piped / Invidious Mirrors (Opt-In, off by default)** | `pipedapi.kavin.rocks`, `pipedapi.adminforge.de`, `api.piped.private.coffee`, `inv.nadeko.net`, `yewtu.be` | When enabled, mirrors are queried only after every primary YouTube request has failed. They receive the track `videoId` and your IP address. |
| **Lyrics Providers** | `lrclib.net`, `lyricsplus.binimum.org`, `lyrics-api.boidu.dev`, `api-lyrics.simpmusic.org` | Fetches synced/plain lyrics (individually toggleable and reorderable in Settings); requests include the title, first artist, album, and duration. |
| **Scrobbling (Opt-In)** | `ws.audioscrobbler.com` (Last.fm), `api.listenbrainz.org` (ListenBrainz) | Submits scrobbles and "now playing" updates when configured with your credentials. |
| **Firebase** | Google Identity / Credential Manager, Firebase Auth, Cloud Firestore | Optional Google Sign-In and cross-device library sync. |

## Build & Test Locally

### Playback identity

To play YouTube audio, the app identifies itself to YouTube's player API using the client identities `IOS_MUSIC`, `ANDROID_VR`, `VISIONOS` and `ANDROID_CREATOR`. Veluna is not affiliated with YouTube.

### Prerequisites

- **JDK 21** and the committed **Gradle Wrapper (`./gradlew`, Gradle 9.3.1)**
- **Android SDK** (`compileSdk 36`, `minSdk 26`, `targetSdk 36`)
- **Node.js 24** and **Firebase CLI** (for Firestore security rules emulator tests & deploying `firestore.rules` + `firestore.indexes.json`)
- A Firebase project config at `app/google-services.json` (gitignored):
  1. Create a Firebase project and register Android app `com.veluna.music` with your release/debug SHA-1 & SHA-256 certificate fingerprints.
  2. Enable **Authentication → Google** and **Cloud Firestore**.
  3. Deploy rules and composite indexes (`firestore.indexes.json`) before serving traffic:
     ```bash
     firebase deploy --only firestore:rules,firestore:indexes
     ```
  4. Download `google-services.json` into `app/google-services.json`.

### Build Commands

```bash
# Signed Release APK (requires RELEASE_KEYSTORE_* environment variables; R8 minified, non-debuggable)
./gradlew :app:assembleRelease

# Android Lint (HardcodedText is enforced as an error)
./gradlew :app:lintDebug
```

### Running Tests

```bash
# 1. Install locked Node test dependencies for Firestore rules testing
npm ci

# 2. Run Firestore security rules & Android unit/Robolectric/Room migration tests + instrumentation test compilation
npx firebase emulators:exec --only firestore,auth "node --test firestore.test.js && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --stacktrace"

# 3. Run Android Keystore hardware-backed instrumentation tests (on an attached device/emulator)
./gradlew :app:connectedDebugAndroidTest
```

## License

See [`LICENSE`](LICENSE).
