#!/usr/bin/env bash

set +e
chmod +x ./gradlew
./gradlew :app:connectedDebugAndroidTest --no-daemon --info --stacktrace 2>&1 | tee android-test-run.log
gradle_status=${PIPESTATUS[0]}
adb logcat -d -s SecretVaultAndroidTest:I > keystore-logcat.txt 2>/dev/null || true
exit "$gradle_status"
