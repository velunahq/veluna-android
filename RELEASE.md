# Release & Signing Guide (`Veluna Android`)

This document describes how Veluna Android release APKs are built, signed, verified, and published through GitHub Actions.

## 1. CI & Release Workflow Overview

The workflow in [`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml) pins third-party GitHub Actions to immutable commit SHAs and runs three jobs:

1. **`test`** *(runs on all PRs, pushes to `main`/`master`, and `v*` tags — requires zero repository secrets)*:
   - Installs locked Node dependencies (`npm ci`) and validates the Gradle wrapper (`./gradlew`, Gradle 9.3.1 with `distributionSha256Sum`).
   - Starts local Firebase Firestore and Auth emulators and runs:
     - `node --test firestore.test.js`
     - `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:compileReleaseKotlin --no-daemon --stacktrace`
   - Fork pull requests only execute the unprivileged `test` and `instrumentation-test` jobs and never receive signing or Firebase credentials.
2. **`instrumentation-test`** *(runs on all PRs, pushes to `main`/`master`, and `v*` tags — requires zero repository secrets)*:
   - Boots a KVM-accelerated Android emulator (`API 34`) and runs `./gradlew :app:connectedDebugAndroidTest` to verify hardware-backed `AndroidKeyStore` (`SecretVault`) encryption and tamper detection on a real Android runtime.
3. **`build-release`** *(runs only on tag pushes on `main`/`master` matching `v*` after both `test` and `instrumentation-test` pass)*:
   - Verifies the release tag belongs to `main` or `master` and fails fast if any required secret or variable is empty.
   - Materializes the release keystore and `app/google-services.json` exclusively in the runner workspace/temp directory (never committed to git).
   - Runs `./gradlew :app:assembleRelease` (`isDebuggable = false`, `isMinifyEnabled = true`, `isShrinkResources = true`) with `velunaVersionCode = 100 + GITHUB_RUN_NUMBER` and `velunaVersionName = <tag without leading 'v'>`.
   - Verifies with `aapt2` that the APK is not debuggable and verifies the signing certificate SHA-256 fingerprint using `apksigner verify --print-certs` against `RELEASE_CERT_SHA256` (failing the build on mismatch).
   - Publishes the signed GitHub Release for that tag with `veluna-android.apk`.

## 2. Required GitHub Secrets & Variables

Configure these in **GitHub Repository → Settings → Secrets and variables → Actions**:

### Secrets
- `ANDROID_RELEASE_KEYSTORE_BASE64`: Base64-encoded release `.jks` or `.keystore` file (`base64 -w0 release.jks`).
- `RELEASE_KEYSTORE_PASSWORD`: Keystore password.
- `RELEASE_KEY_ALIAS`: Signing key alias inside the keystore.
- `RELEASE_KEY_PASSWORD`: Private key password for `RELEASE_KEY_ALIAS`.
- `GOOGLE_SERVICES_JSON`: Complete contents of `app/google-services.json` configured for the release Firebase project + release SHA-1/SHA-256.

### Repository Variables (Public)
- `RELEASE_CERT_SHA256`: The colon-less or colon-formatted hex SHA-256 certificate digest of `RELEASE_KEY_ALIAS` (compared case-insensitively against `apksigner verify --print-certs`).

## 3. Cutting a Tagged Release

1. Ensure `main` passes all tests and `./gradlew :app:lintDebug` locally.
2. Create and push an annotated semantic version tag:
   ```bash
   git tag -a v1.2.0 -m "Veluna v1.2.0"
   git push origin v1.2.0
   ```
3. Monitor the **Veluna CI & Release APK** workflow run in GitHub Actions and confirm the published release asset `veluna-android.apk` and release notes.

## 4. Verifying a Downloaded Release APK

Users and maintainers can verify that a downloaded `veluna-android.apk` was signed by the official release key:

```bash
apksigner verify --print-certs veluna-android.apk
```

Check that `Signer #1 certificate SHA-256 digest` matches `RELEASE_CERT_SHA256` and confirm `Verified using v2 scheme (APK Signature Scheme v2): true` (or v3).

## 5. Keystore Generation & Rotation Procedure

If you need to generate a fresh release keystore or rotate a compromised key:

1. **Generate a new keystore** (store offline in a password manager / HSM — never commit to git):
   ```bash
   keytool -genkeypair -v \
     -keystore veluna-release.jks \
     -storetype PKCS12 \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -alias veluna-release
   ```
2. **Inspect the new SHA-1 and SHA-256 fingerprints**:
   ```bash
   keytool -list -v -keystore veluna-release.jks -alias veluna-release
   ```
3. **Update Firebase & Google Cloud OAuth**:
   - Add the new SHA-1 and SHA-256 fingerprints to your Android app (`com.veluna.music`) in Firebase Console and create/update the corresponding Android OAuth client in Google Cloud Console.
   - Restrict the Firebase API key in Google Cloud Console to Android app `com.veluna.music` and your release certificate SHA-1 fingerprint.
   - Download the updated `google-services.json`.
4. **Update GitHub Secrets & Variable**:
   - Encode the new keystore (`base64 -w0 veluna-release.jks`) into `ANDROID_RELEASE_KEYSTORE_BASE64`, along with `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and `GOOGLE_SERVICES_JSON`.
   - Update repository variable `RELEASE_CERT_SHA256` to the new SHA-256 hex digest.
5. **Remove old fingerprints** in Firebase / Google Cloud once legacy builds are retired, and announce the rotation in the release notes.
