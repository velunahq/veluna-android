# AGENT.md - Veluna

Context file for AI agents (Antigravity, Claude Code, etc.) working in this repo.
Keep this file up to date as the project evolves — it's the fastest way to give
the agent full context without re-scanning the whole codebase every session.

## Development Rules & Guidelines

> **Maintenance rule:** whenever you add or change a feature, structure,
> module, build config, or convention in this repo, **update this file in
> the same PR/commit**. This file is only useful if it stays accurate —
> stale context is worse than no context, because the agent will act on it
> confidently and be wrong. Treat an out-of-date `AGENT.md` as a bug.

> **Dynamic Design Pattern rule:** Whenever you make ANY changes or updates to the UI, layout, or components, you **must automatically document** the new or updated design patterns directly in this `AGENT.md` file (and `DESIGN.md` if applicable) during the same session. This ensures that the AI's context regarding the project's visual aesthetic is dynamically kept up-to-date, so future tasks follow the newly introduced patterns accurately.

> **Branding & Identity Rule:**
> - App name is strictly **Veluna** everywhere (manifests, launcher labels, notifications, settings, UI text, and locale files).
> - Versioning is strictly `versionName: "0.1.0"`, `versionCode: 1`. Always display `0.1.0` wherever version is shown.
> - **Zero telemetry & bloat:** No Firebase, Google Analytics, telemetry, crash reporting, ads, donation links, crypto addresses, sponsor banners, or community/Discord links. Build as clean FOSS.
> - **Zero updater logic:** Completely exclude any version-check, in-app updater, or update-dialog logic.
> - **Branding & Assets:** Use the Veluna "V" logo exclusively for launcher icons, splash screen, monochrome notifications, and vector graphics.
> - **Application ID:** Application ID is `com.github.veluna` (debug suffix `.debug`). Never rename to any other app's package name.
> - Zero visible hits for legacy reference names across code, manifests, and locale files (permitted only in third-party license credit/headers).

> **Attribution rule:** if a feature is ported from, adapted from, or
> inspired by another open-source project (even partially — a UI pattern,
> an algorithm, a parsing approach, a whole file), you **must** add or
> update an entry in `README.md`'s **Special Thanks** section in the same
> commit. Follow the existing table format there:
> `| **[Project Name](repo URL)** | What was taken/inspired, described specifically |`.
> Be specific in the description — not "inspiration" alone, but what part,
> matching how existing entries are described. If code is copied or closely
> adapted rather than just conceptually inspired, say so plainly in the
> description, and check the source project's license is compatible before merging.
> Never port/adapt code without adding this credit.

> **Do not push without explicit instruction.** Making code changes
> (editing files, committing locally) is fine whenever asked, but **never
> run `git push` — or otherwise publish changes to GitHub — unless explicitly told to push**.

> **Pre-push checklist — run through this every time before pushing:**
>
> 1. **Remove all temporary files.** Delete any scratch/debug/automation scripts.
> 2. **Verify the build is clean.** Run `./gradlew :app:assembleFossDebug` and confirm `BUILD SUCCESSFUL`.
> 3. **Write a proper commit message.** Conventional Commits format (`type(scope): summary`).
> 4. **Update `AGENT.md` and docs** if module structure, conventions, or architecture changed.

### UI rule: Nothing OS 5.0 Design (over Material You)

All UI work — new screens, new components, and edits to existing ones —
**must** follow the **Nothing OS 5.0 design** language (clean and minimal) rather than blindly
applying standard Material Design 3 guidelines or Material You.

We maintain a dedicated **[DESIGN.md](DESIGN.md)** file which serves as the central
hub for all design decisions, component usage, and style choices in the Veluna codebase.

**Key Requirements:**
- **Clean and Minimal:** Emphasize the Nothing OS 5.0 aesthetic. Focus on high-contrast, typography-driven layouts, monochrome or minimal color palettes, and distinct geometry.
- **Avoid standard M3 containers:** Do not use standard M3 containers or generic Material You bouncy/rounded components. Replace them with stark, clean Nothing-style UI.
- **Do not refactor to strict M3:** Do not replace existing minimal components with `LargeTopAppBar` or standard M3 cards.

### Conventions worth following

- Kotlin official style: `val` over `var`, data classes for simple holders,
  sealed classes/interfaces for UI/playback state.
- New dependency versions go in `gradle/libs.versions.toml`
  (`[versions]` + `[libraries]`), referenced via `libs.xxx` — don't hardcode
  version strings in module `build.gradle.kts` files.
- New cross-cutting integrations (lyrics source, canvas provider, metadata
  source) → new Gradle module, not a package inside `:app`.
- Feature flags / build-time toggles go through `buildConfigField` in
  `app/build.gradle.kts`.
- Room schema changes require a migration in `db/` and the schema JSON is
  version-controlled under `app/schemas/` — don't skip migrations.
- **Networking:** Use Ktor for all new network requests. Retrofit is maintained for legacy endpoints but should not be used for new features.

## What this app is

Veluna is a native **Android** music streaming client (Kotlin + Jetpack
Compose) that streams from YouTube Music's catalog ad-free, and layers on
offline downloads, real-time synced lyrics, music recognition ("Veluna Find"),
podcasts, local media playback, Spotify import, "Listen Together" (synced
group listening), Discord Rich Presence, and more. It builds as a clean **FOSS**
application free from external telemetry, trackers, and proprietary bloat.

Package/namespace: `com.veluna` (app module). Application ID: `com.github.veluna`.

## Tech stack

- **Language:** Kotlin (JVM target 21), Gradle Kotlin DSL (`.kts`)
- **UI:** Jetpack Compose (Material 3, `material3 = 1.5.0-alpha18`), adaptive
  layouts (`androidx.compose.material3.adaptive`), `haze` for blur effects,
  `materialKolor` for dynamic color, Coil 3 for image loading, Lottie for animations,
  `shimmer` for loading placeholders, `smoothCorner` for custom shapes.
- **Architecture:** MVVM — ViewModels (`viewmodels/`) + Compose screens (`ui/screens/`)
  + Repository-ish data layer under `data/`/`db/`. Hilt (`dagger.hilt`) is used throughout for DI.
- **Persistence:** Room (`db/entities/`, `db/daos/`), DataStore Preferences (`utils/dataStore`).
- **Playback:** Media3 / ExoPlayer (`media3`, `media3-session`, `media3-hls`,
  `media3-ui`, `media3-okhttp`) — core logic in `playback/` (`MusicService.kt`,
  `PlayerConnection.kt`, `ExoDownloadService.kt`, `ChunkingDataSource.kt`,
  `SleepTimer.kt`, `audio/`, `queues/`). FFmpegKit is used for audio export.
- **Networking:** Ktor client + OkHttp, Jsoup for scraping, kotlinx.serialization + Gson.
- **Build system:** Gradle version catalog at `gradle/libs.versions.toml`. AGP `9.0.0`, Kotlin `2.3.10`,
  KSP for annotation processing (Room, Hilt).

## Module map (multi-module Gradle project)

Root `:app` depends on all of these library modules:

| Module | Purpose |
|---|---|
| `:app` | Main application — UI, ViewModels, MusicService, DB, DI, app-level orchestration |
| `:core` | Shared models, constants, Room database, DataStore, and common utilities |
| `:playback` | Pure ExoPlayer/Media3 logic — Queues, Equalizer, ChunkingDataSource, SleepTimer, BeatAnalyzer |
| `:lyrics` | Lyrics orchestration — LyricsHelper, LyricsEntry, LyricsUtils, all provider impls, AI translation |
| `:innertube` | YouTube Music InnerTube API client (the core music source) |
| `:kugou`, `:lrclib`, `:betterlyrics`, `:youlyplus`, `:paxsenixlyrics`, `:simpmusic` | Individual lyrics source providers (each consumed by `:lyrics`) |
| `:shazamkit` | Music recognition ("Veluna Find") |
| `:canvas`, `:applecanvas` | Canvas-style looping video backgrounds for tracks |
| `:artistvideo` | Artist video features |
| `:unison` | Cross-cutting shared utility module |

## App module internal structure

Path: `app/src/main/kotlin/com/music/echo/`

```
ai/             AI features (lyrics translation providers, etc.)
api/            External API clients not covered by a dedicated module
constants/      Preference keys / constant definitions (DataStore keys live here)
data/           Data layer glue
db/
  entities/     Room entities (Song, Album, Artist, Playlist, Lyrics, etc.)
  daos/         Room DAOs
di/             Hilt modules (AppModule, NetworkModule, Qualifiers, entry points)
discord/        Discord Rich Presence integration
eq/             Equalizer
extensions/     Kotlin extension functions
listentogether/ "Listen Together" synced group listening feature
localmedia/     Local on-device media file playback
lyrics/         Lyrics orchestration (aggregates the lyrics provider modules)
models/         Shared data models
playback/       Media3/ExoPlayer service, download manager, queueing, audio
quicksettings/  Android quick settings tile
recognition/    Music recognition (Veluna Find) app-side logic
spotify/        Spotify API integration
spotifyimport/  Import playlists/tracks from Spotify
ui/
  component/    Reusable Compose components (backdrop, floating tab bar, shimmer, etc.)
  menu/         Context/dropdown menus
  player/       Now-playing / player UI
  screens/      Top-level screens (Home, Search, Library, Album, Artist, Settings, etc.)
  theme/        Theme.kt, Type.kt, Font.kt, color extraction, dynamic color
  utils/        UI-specific utilities
utils/          General utilities
viewmodels/     ViewModels, one (or a few) per screen/feature
widget/         Home-screen widgets (Veluna Modern, Classic, Compact, etc.)
```

## UI / design system notes

- **Nothing OS 5.0 / Glassmorphism Aesthetic:**
  - Translucent / frosted glass effect: use custom `Modifier.liquidGlass(config = ...)`.
  - For a **dense, clean blur**, use a high `blurRadius` (e.g., `64f`) and explicitly set `surfaceTintColor` to `MaterialTheme.colorScheme.surface` or `Color.Black`, with moderate `surfaceOpacity` (e.g., `0.5f`).
  - **Flat Integration:** In floating toolbars or navbars, integrate action buttons directly as flat `FloatingNavigationToolbarActionItem`s or `StandardFloatingActionButton`s with `elevation = 0.dp`.
- Material 3 with dynamic color and "pure black" dark mode support.
- Adaptive layouts (`androidx.compose.material3.adaptive`) across phone/tablet/foldable widths.
- Shimmer loading placeholders (`ui/component/shimmer/`, `libs.shimmer`) as the standard loading state.

## Commit message format (required)

```
<type>(<scope>): <short, imperative summary>
```

Rules:
- **type**: `feat`, `fix`, `build`, `chore`, `refactor`, `docs`, `perf`, `test`, or `ci`.
- **scope**: the module or area touched, lowercase (e.g. `ui`, `db`, `playback`, `lyrics`).
- **summary**: imperative mood, no trailing period.
- **body**: explanation of what changed and why.

## Build variants

**Clean FOSS build.** Min/target/compile SDK: `minSdk 26`, `targetSdk 36`, `compileSdk 36`. JDK 21.

```bash
# Debug build
./gradlew assembleFossDebug
```

## Local Taste Engine

The Local Taste Engine (`com.veluna.generate`) provides offline, account-free algorithmic recommendation and smart mix generation:
- Pure local scoring, Last.fm taste graphs, genre weights, time-of-day momentum, and skip penalties.
- High-fidelity recommendation relations and deterministic seeded selections.
