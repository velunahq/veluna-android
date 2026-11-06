# Veluna for Android

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon_round.webp" alt="Veluna Logo" width="100" />
</p>

<p align="center">
  A clean, minimalist music player for Android. Built with Jetpack Compose.
</p>

<p align="center">
  Zero ads. Zero tracking. No accounts required.
</p>

---

## Desktop Version

Looking for the desktop client? Download Veluna for Windows, macOS, and Linux from the desktop repository:
https://github.com/velunahq/veluna

---

## Features

- Background playback with media session and lock screen controls
- Audio streaming with low latency and gapless transitions
- Synchronized and static lyrics display
- Search across songs, albums, artists, and playlists
- High-quality offline audio caching
- Modern Material 3 interface with dynamic theming
- Android Auto integration
- Local backup and restore for playlists and preferences
- Sleep timer and audio normalization support

---

## Download

Get the latest release APK from the [Releases](https://github.com/velunahq/veluna-android/releases) page.

---

## Building from Source

### Prerequisites

- Java Development Kit (JDK) 17 or higher
- Android SDK with Platform 35 or higher installed

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/velunahq/veluna-android.git
   cd veluna-android
   ```

2. Build the debug APK:
   ```bash
   ./gradlew assembleGithubDebug
   ```

3. The generated APK will be located at:
   ```
   app/build/outputs/apk/github/debug/app-github-debug.apk
   ```

4. Install to a connected device:
   ```bash
   adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
   ```

---

## License

This project is licensed under the GNU General Public License v3.0 (GPL-3.0). See the [LICENSE](LICENSE) file for details.
